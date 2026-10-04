package dev.supermux.desktop.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the desktop knows about this computer's power, detected locally (the broker is not asked):
 * whether it has a battery ("Also on battery" and "Even with the lid closed" are for laptops only)
 * and, on macOS, whether FileVault is off (then Automatic Login can start supermux after a reboot).
 * The parsers are pure; [PowerFactsCache] runs the probes once, off the UI thread, and caches them.
 */
object PowerFacts {
    /** `pmset -g batt`: a laptop lists `-InternalBattery-0`; a desktop Mac only says "AC Power". */
    fun macHasBattery(pmsetBatt: String): Boolean = pmsetBatt.contains("InternalBattery")

    /**
     * Linux: a `/sys/class/power_supply/<x>` entry with `type` = Battery whose `scope` is not
     * Device (a wireless mouse or headset reports scope Device).
     */
    fun linuxHasBattery(powerSupply: Path = Path.of("/sys/class/power_supply")): Boolean = runCatching {
        if (!Files.isDirectory(powerSupply)) return false
        Files.list(powerSupply).use { entries ->
            entries.anyMatch { dir ->
                val type = readTrimmed(dir.resolve("type"))
                val scope = readTrimmed(dir.resolve("scope"))
                type.equals("Battery", ignoreCase = true) && !scope.equals("Device", ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    /** Windows: the count of `Win32_Battery` instances PowerShell printed. */
    fun windowsHasBattery(countOutput: String?): Boolean = countOutput?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    /** `fdesetup status` → true when it says "FileVault is Off.". */
    fun fileVaultOff(fdesetupStatus: String?): Boolean = fdesetupStatus?.contains("FileVault is Off") == true

    val WINDOWS_BATTERY_ARGV = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "(Get-CimInstance -ClassName Win32_Battery | Measure-Object).Count",
    )
    val MAC_BATTERY_ARGV = listOf("/usr/bin/pmset", "-g", "batt")
    val FDESETUP_ARGV = listOf("/usr/bin/fdesetup", "status")

    /** Probe whether this computer has a battery. Blocking; never throws. */
    fun detectBattery(os: OsEnv): Boolean = when (os.os) {
        OsEnv.Os.MAC -> os.runCapture(MAC_BATTERY_ARGV)?.let(::macHasBattery) ?: false
        OsEnv.Os.LINUX -> linuxHasBattery()
        OsEnv.Os.WINDOWS -> windowsHasBattery(os.runCapture(WINDOWS_BATTERY_ARGV))
        OsEnv.Os.OTHER -> false
    }

    /** macOS only: FileVault is off. Null elsewhere or when it can't tell. Blocking; never throws. */
    fun detectFileVaultOff(os: OsEnv): Boolean? =
        if (os.os != OsEnv.Os.MAC) null else os.runCapture(FDESETUP_ARGV)?.let(::fileVaultOff)

    private fun readTrimmed(p: Path): String = runCatching { Files.readString(p).trim() }.getOrDefault("")
}

/** [PowerFacts] probed once, asynchronously. Null until known. */
class PowerFactsCache(
    private val os: OsEnv,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val battery: (OsEnv) -> Boolean = PowerFacts::detectBattery,
    private val fileVault: (OsEnv) -> Boolean? = PowerFacts::detectFileVaultOff,
) {
    private val started = AtomicBoolean(false)
    private val _hasBattery = MutableStateFlow<Boolean?>(null)
    val hasBattery: StateFlow<Boolean?> = _hasBattery.asStateFlow()
    private val _fileVaultOff = MutableStateFlow<Boolean?>(null)
    val fileVaultOff: StateFlow<Boolean?> = _fileVaultOff.asStateFlow()

    /** Starts the probes once; later calls do nothing. */
    fun load(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            _hasBattery.value = withContext(io) { runCatching { battery(os) }.getOrDefault(false) }
        }
        scope.launch {
            _fileVaultOff.value = withContext(io) { runCatching { fileVault(os) }.getOrNull() }
        }
    }
}
