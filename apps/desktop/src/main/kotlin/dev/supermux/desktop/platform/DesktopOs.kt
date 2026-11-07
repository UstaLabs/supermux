// The desktop machine's OS, and the commands this app hands to it. Step 1 of the platform layer
// (docs/superpowers/specs/2026-11-03-platform-layer-design.md §3.4, §5): `os.name` is read here, and
// no command built here carries an untrusted value inside text that a shell or an interpreter
// parses. Card #40 is why: read-aloud spliced agent text into a PowerShell script, and opening an
// attachment handed its name to `cmd /c start`. Still to merge in step 9: `OsCommand.start` beside
// `host/OsEnv` (the audit's one process runner), `which` beside `OsEnv.hasCommand`, and this enum
// with `DesktopOs`, whose `OTHER` `LINUX` stands in for here.
package dev.supermux.desktop.platform

import java.util.Locale

/**
 * Which OS the desktop app runs on. [LINUX] also stands for any other Unix: the freedesktop tools
 * are the only guess there, as they always were in the openers this replaces.
 */
enum class DesktopOs {
    WINDOWS, MACOS, LINUX;

    companion object {
        /** Mac first: "Darwin" contains "win". */
        fun of(osName: String?): DesktopOs {
            val os = osName?.lowercase(Locale.US).orEmpty()
            return when {
                os.contains("mac") || os.contains("darwin") -> MACOS
                os.startsWith("windows") -> WINDOWS
                else -> LINUX
            }
        }

        val current: DesktopOs by lazy { of(System.getProperty("os.name")) }
    }
}

/**
 * A program to start, the desktop app's one way to start one (#29): [argv] as separate arguments, [env] added to this
 * process's environment, or the child's whole environment when [replaceEnv], run in [workDir] when set. An untrusted
 * value rides in [env] (PowerShell reads it as `$env:NAME`) or as one whole argument, never inside a script.
 */
internal data class OsCommand(
    val argv: List<String>,
    val env: Map<String, String> = emptyMap(),
    val replaceEnv: Boolean = false,
    val workDir: java.nio.file.Path? = null,
) {
    /**
     * Start it, stdout to [output] and stderr to [errors], or into [output] as well when null. Stdin is closed at once:
     * nothing is ever written to these children, so none may wait for input that will not come.
     */
    fun start(output: ProcessBuilder.Redirect = ProcessBuilder.Redirect.DISCARD, errors: ProcessBuilder.Redirect? = null): Process {
        val builder = ProcessBuilder(argv).redirectOutput(output)
        if (errors == null) builder.redirectErrorStream(true) else builder.redirectError(errors)
        workDir?.let { builder.directory(it.toFile()) }
        if (replaceEnv) builder.environment().clear()
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        return process
    }
}

/** Windows PowerShell by its System32 path, never whatever `powershell.exe` the search path finds first. */
internal fun windowsPowerShell(systemRoot: String? = System.getenv("SystemRoot")): String =
    (systemRoot?.takeIf { it.isNotBlank() }?.trimEnd('\\') ?: "C:\\Windows") +
        "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"

/**
 * Run [script], a constant, in Windows PowerShell with [data] as environment variables. PowerShell
 * never parses a variable's value as code, so no quoting rule can be missed — the old read-aloud
 * doubled the ASCII `'` and missed the four other quote characters PowerShell ends a string at
 * (U+2018–U+201B). [script] must not contain `"`: Java quotes a Windows argument without escaping
 * the quotes inside it.
 */
private fun windowsPowerShellScript(script: String, data: Map<String, String>, systemRoot: String?): OsCommand = OsCommand(
    listOf(windowsPowerShell(systemRoot), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script),
    data,
)

private const val SPEAK_TEXT = "SUPERMUX_SPEAK_TEXT"
private const val OPEN_TARGET = "SUPERMUX_OPEN_TARGET"

/** System.Speech; `Speak` returns once the text is spoken, as `say` and `spd-say -w` do. */
private const val SPEAK_SCRIPT = "Add-Type -AssemblyName System.Speech; " +
    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak(\$env:$SPEAK_TEXT)"

/**
 * ShellExecute with the default verb, through .NET. `ErrorDialog` lets the shell show its own UI:
 * "How do you want to open this file?" for a type with no app, which is the usual reason
 * `Desktop.open` failed and this runs at all, as `cmd /c start` did before.
 */
private const val OPEN_SCRIPT =
    "\$i = New-Object System.Diagnostics.ProcessStartInfo \$env:$OPEN_TARGET; \$i.UseShellExecute = \$true; " +
        "\$i.ErrorDialog = \$true; [void][System.Diagnostics.Process]::Start(\$i)"

/**
 * Open [target], an absolute path or a URL, with the OS default handler: ShellExecute through
 * PowerShell on Windows — never `cmd /c start`, which runs whatever follows an `&` in a file name —
 * `open` on macOS, `xdg-open` elsewhere. Those two take it as one argument, so a target starting
 * with `-` would read as their option; it is refused instead.
 */
internal fun openCommand(
    target: String,
    os: DesktopOs = DesktopOs.current,
    systemRoot: String? = System.getenv("SystemRoot"),
): OsCommand {
    require(!target.startsWith("-")) { "an opener takes an absolute path or a URL" }
    return when (os) {
        DesktopOs.WINDOWS -> windowsPowerShellScript(OPEN_SCRIPT, mapOf(OPEN_TARGET to target), systemRoot)
        DesktopOs.MACOS -> OsCommand(listOf("open", target))
        DesktopOs.LINUX -> OsCommand(listOf("xdg-open", target))
    }
}

/**
 * The OS speech command for [plain], or null when this machine has no synthesiser we know of. The
 * probe order IS the behaviour: `say` on macOS, System.Speech on Windows, then
 * `spd-say`/`espeak-ng`/`espeak`.
 *
 * The text is the agent's, so it is always data: an environment variable for PowerShell, the one
 * argument after `--` everywhere else (`say --output-file=…` would write a file). `spd-say` is never
 * in pipe mode (`-e`): that ignores the argument and sends any stdin line starting with `!-!` to
 * speech-dispatcher as a command.
 *
 * Killing `spd-say` does not silence it: speech-dispatcher finishes a message whose client
 * disconnects, and only an SSIP `CANCEL` stops it (`spd-say -C` cancels every client's speech).
 */
internal fun speechCommand(
    plain: String,
    os: DesktopOs = DesktopOs.current,
    systemRoot: String? = System.getenv("SystemRoot"),
    has: (String) -> Boolean = ::which,
): OsCommand? = when (os) {
    DesktopOs.MACOS -> OsCommand(listOf("say", "--", plain))
    DesktopOs.WINDOWS -> windowsPowerShellScript(SPEAK_SCRIPT, mapOf(SPEAK_TEXT to plain), systemRoot)
    DesktopOs.LINUX -> when {
        has("spd-say") -> OsCommand(listOf("spd-say", "-w", "--", plain))
        has("espeak-ng") -> OsCommand(listOf("espeak-ng", "--", plain))
        has("espeak") -> OsCommand(listOf("espeak", "--", plain))
        else -> null
    }
}

/** Whether [bin] is a command on this process's PATH, found in-process with this OS's rules ([findCommand]). */
internal fun which(bin: String): Boolean = findCommand(bin) != null
