package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This computer's name, the one its OS shows ("Ahmet's MacBook Air"), for pairing, Add host and the broker.
 *
 * #133: the name used to come from InetAddress's local-host lookup (DNS), and the first composition waited up to
 * 10 s for it on the UI thread: the window and the broker socket both stood still for those 10 s wherever the lookup
 * stalled (a GitHub macOS runner every time; slow or broken DNS for a user). It also answered "192.168.1.116" on a Mac
 * whose DHCP set the hostname to its IP. Now the name comes from the OS's own setting, read on a daemon thread
 * ([refresh], started first thing in main), and [now] never waits: the read name, else the one cached at the last
 * launch, else a plain fallback.
 */
class HostName internal constructor(
    private val read: () -> String?,
    private val cacheFile: Path?,
    private val fallback: String,
) {
    private val _name = MutableStateFlow(readCache() ?: fallback)

    /** The best name known now; updates when [refresh] has read it. Never blocks. */
    val name: StateFlow<String> = _name.asStateFlow()

    private val done = CountDownLatch(1)
    @Volatile private var started = false

    /** The best name known now, without waiting. Safe on the UI thread. */
    fun now(): String = _name.value

    /** Reads the OS's name once, on its own daemon thread. Later calls do nothing. */
    fun refresh() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            try {
                val read = runCatching { read() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                if (read != null) {
                    _name.value = read
                    if (read != readCache()) runCatching { cacheFile?.let { Files.createDirectories(it.parent); Files.writeString(it, read) } }
                }
            } finally {
                done.countDown()
            }
        }, "host-name").apply { isDaemon = true }.start()
    }

    /** The name once [refresh] has read it, waiting at most [timeoutMs]. Never call it where the UI waits. */
    fun awaitRead(timeoutMs: Long): String {
        refresh()
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return now()
    }

    private fun readCache(): String? =
        runCatching { cacheFile?.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trim() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        /** The app's one instance: the OS's name, cached in the broker state dir. */
        val shared: HostName by lazy {
            HostName(
                read = { osName(DesktopOs.current, System::getenv, ::runCommand, ::readFile) },
                cacheFile = runCatching { BrokerPaths.defaultStateDir().resolve("this-computer-name") }.getOrNull(),
                fallback = "Desktop host",
            )
        }

        /**
         * The name as the OS keeps it, with no network: macOS's Computer Name (System Settings › General › Sharing),
         * else its Bonjour LocalHostName; Windows' COMPUTERNAME; Linux's /etc/hostname, else `hostname`. Null when
         * none answers.
         */
        internal fun osName(
            os: DesktopOs,
            env: (String) -> String?,
            command: (List<String>) -> String?,
            file: (String) -> String?,
        ): String? {
            fun String?.clean() = this?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            return when (os) {
                DesktopOs.MACOS ->
                    command(listOf("/usr/sbin/scutil", "--get", "ComputerName")).clean()
                        ?: command(listOf("/usr/sbin/scutil", "--get", "LocalHostName")).clean()
                DesktopOs.WINDOWS -> env("COMPUTERNAME").clean()
                DesktopOs.LINUX -> file("/etc/hostname").clean() ?: command(listOf("hostname")).clean()
            }
        }

        private fun runCommand(argv: List<String>): String? = runCatching {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@runCatching null
            }
            p.inputStream.bufferedReader().readText().takeIf { p.exitValue() == 0 }
        }.getOrNull()

        private fun readFile(path: String): String? = runCatching { Files.readString(Path.of(path)) }.getOrNull()
    }
}
