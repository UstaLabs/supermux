// Whether this desktop has a microphone, and whether its OS keeps it from supermux (#62): what the assistant
// overlay says when hold-to-talk can't open the mic. Java Sound can't tell: on Linux it always lists the sound
// server's "default" device, microphone or not. So each OS's own word is read: PipeWire's (or PulseAudio's) sources on
// Linux, Windows' privacy switches and recording endpoints in its registry. macOS answers through AVFoundation
// (overlay/MacOverlayNative). The readers are pure; [askMicTrouble] runs the commands, for DesktopMicCapture.
package dev.supermux.desktop.platform

import dev.supermux.desktop.overlay.MacOverlayNative
import dev.supermux.ui.platform.MicTrouble
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What to say, from whether there is a microphone ([hasMic]) and whether the OS keeps it from this app ([denied]),
 * null where the OS couldn't be asked. None at all comes first: allowing a microphone that isn't there wouldn't
 * help. Nothing known is never a guess.
 */
internal fun micTroubleOf(hasMic: Boolean?, denied: Boolean?): MicTrouble = when {
    hasMic == false -> MicTrouble.NONE_FOUND
    denied == true -> MicTrouble.NOT_ALLOWED
    else -> MicTrouble.DIDNT_OPEN
}

/**
 * Whether `pw-dump`'s objects hold something to record a voice from: a node whose media class is an audio source
 * (a sound card's input, a USB or Bluetooth mic, a noise-cancelling filter's source). An app's recording stream is
 * not one. Null when [dump] isn't a dump at all.
 */
internal fun pipeWireHasMic(dump: String): Boolean? {
    val objects = runCatching { Json.parseToJsonElement(dump) as? JsonArray }.getOrNull() ?: return null
    return objects.any { o ->
        val props = ((o as? JsonObject)?.get("info") as? JsonObject)?.get("props") as? JsonObject
        val mediaClass = props?.get("media.class")?.jsonPrimitive?.contentOrNull.orEmpty()
        mediaClass == "Audio/Source" || mediaClass.startsWith("Audio/Source/")
    }
}

/** Whether `pactl list short sources` lists a microphone: any source but a sink's monitor, which records what plays. */
internal fun pulseHasMic(sources: String): Boolean =
    sources.lineSequence().mapNotNull { it.split('\t').getOrNull(1) }.any { !it.endsWith(".monitor") }

/**
 * Whether Windows keeps the microphone from desktop apps: any of its switches (`reg query … /v Value` of the
 * microphone's consent store, for this device, this user, and desktop apps) set to Deny. A switch never touched has
 * no value, which is allowed.
 */
internal fun windowsMicDenied(consent: String): Boolean =
    consent.lineSequence().any { Regex("""^\s*Value\s+REG_SZ\s+Deny\s*$""", RegexOption.IGNORE_CASE).matches(it) }

/**
 * Whether Windows has a recording endpoint that works (`reg query …\MMDevices\Audio\Capture /s /v DeviceState`):
 * state 1. 2 is switched off, 4 gone, 8 a jack with nothing in it. No such key at all: there never was one. Null
 * when the registry wouldn't say.
 */
internal fun windowsHasMic(states: String): Boolean? {
    if (states.contains("unable to find", ignoreCase = true)) return false
    val found = Regex("""^\s*DeviceState\s+REG_DWORD\s+0x([0-9a-f]+)\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        .findAll(states).map { it.groupValues[1].toLong(16) }.toList()
    if (found.isEmpty() && !states.contains("End of search", ignoreCase = true)) return null
    return found.any { it == DEVICE_STATE_ACTIVE }
}

private const val DEVICE_STATE_ACTIVE = 1L

/** Asks [os] why the mic won't open. Blocks for the commands it runs (a few hundred ms at most): call it off the UI thread. */
internal fun askMicTrouble(os: DesktopOs): MicTrouble = when (os) {
    // No app asks a Linux desktop for its microphone: it is there, or it isn't.
    DesktopOs.LINUX -> micTroubleOf(hasMic = linuxHasMic(), denied = false)
    DesktopOs.WINDOWS -> micTroubleOf(
        hasMic = printedBy(reg("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Capture", "/s", "/v", "DeviceState"))?.let(::windowsHasMic),
        denied = CONSENT_KEYS.mapNotNull { printedBy(reg(it, "/v", "Value")) }.joinToString("\n").let(::windowsMicDenied),
    )
    DesktopOs.MACOS -> micTroubleOf(hasMic = MacOverlayNative.hasMic(), denied = MacOverlayNative.micDenied())
}

/** PipeWire's word first (every recent desktop runs it), then PulseAudio's; null with neither to ask. */
private fun linuxHasMic(): Boolean? {
    if (which("pw-dump")) printedBy(OsCommand(listOf("pw-dump")), okOnly = true)?.let(::pipeWireHasMic)?.let { return it }
    if (which("pactl")) printedBy(OsCommand(listOf("pactl", "list", "short", "sources")), okOnly = true)?.let { return pulseHasMic(it) }
    return null
}

/** Windows' microphone switches: for this device, for this user, and for desktop apps (what supermux is). */
private val CONSENT_KEYS = listOf(
    "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone",
    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone",
    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone\\NonPackaged",
)

/** `reg query` by its System32 path, as PowerShell is run ([windowsPowerShell]). */
private fun reg(key: String, vararg args: String, systemRoot: String? = System.getenv("SystemRoot")): OsCommand = OsCommand(
    listOf((systemRoot?.takeIf { it.isNotBlank() }?.trimEnd('\\') ?: "C:\\Windows") + "\\System32\\reg.exe", "query", key) + args,
)

/**
 * What [command] printed, its errors with it ("unable to find" is an answer too), read while it runs so a long dump
 * can't fill the pipe and stall it. Null when it couldn't start, took over [seconds], or failed and [okOnly].
 */
private fun printedBy(command: OsCommand, okOnly: Boolean = false, seconds: Long = 3): String? = runCatching {
    val process = command.start(output = ProcessBuilder.Redirect.PIPE, errors = null)
    val printed = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (okOnly && process.exitValue() != 0) return null
    printed.get(1, TimeUnit.SECONDS)
}.getOrNull()
