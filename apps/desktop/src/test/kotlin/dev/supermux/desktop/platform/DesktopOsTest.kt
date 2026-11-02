package dev.supermux.desktop.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Card #40. Agent text and attachment names are untrusted, so no command this app starts may carry
 * them inside a string that a shell or an interpreter parses: PowerShell gets them through an
 * environment variable, every other program after `--` or as an absolute path.
 *
 * The PowerShell tests run the real script in a real PowerShell (`pwsh`, or Windows PowerShell on
 * Windows) with `Add-Type` and `New-Object` shadowed by functions that record what they receive, so
 * nothing is spoken and nothing is opened. Without a PowerShell they are skipped; the argv tests
 * above them pin the same rule everywhere.
 */
class DesktopOsTest {

    /** PowerShell also ends a '…' string at U+2018–U+201B, so doubling only ASCII ' let this run Set-Content. */
    private val quoteBreakout = "hello\u2018); Set-Content -Path pwned -Value INJECTED; (\u2018"

    /** cmd.exe runs what follows an unquoted &, and Java quotes an argument only when it has a space or tab. */
    private val ampersandPath = "C:\\Users\\a\\AppData\\Local\\Temp\\supermux-attachments\\report&calc.txt"

    @Test
    fun `the OS is read from os name in one place`() {
        assertEquals(DesktopOs.WINDOWS, DesktopOs.of("Windows 11"))
        assertEquals(DesktopOs.WINDOWS, DesktopOs.of("Windows Server 2022"))
        assertEquals(DesktopOs.MACOS, DesktopOs.of("Mac OS X"))
        // "Darwin" contains "win": it must not read as Windows.
        assertEquals(DesktopOs.MACOS, DesktopOs.of("Darwin"))
        assertEquals(DesktopOs.LINUX, DesktopOs.of("Linux"))
        assertEquals(DesktopOs.LINUX, DesktopOs.of("FreeBSD"))
        assertEquals(DesktopOs.LINUX, DesktopOs.of(null))
    }

    @Test
    fun `windows read-aloud passes the text in the environment, not in the script`() {
        val cmd = assertNotNull(speechCommand(quoteBreakout, DesktopOs.WINDOWS) { false })
        assertTrue(cmd.argv.none { "Set-Content" in it }, "the text reached the command line: ${cmd.argv}")
        assertEquals(listOf(quoteBreakout), cmd.env.values.toList())
    }

    @Test
    fun `windows read-aloud starts the same command line whatever the text says`() {
        val plain = assertNotNull(speechCommand("hello", DesktopOs.WINDOWS) { false })
        val hostile = assertNotNull(speechCommand(quoteBreakout, DesktopOs.WINDOWS) { false })
        assertEquals(plain.argv, hostile.argv)
        // Java on Windows wraps an argument in quotes without escaping the ones inside it.
        assertTrue(plain.argv.none { '"' in it }, "a double quote would break Java's Windows quoting: ${plain.argv}")
    }

    @Test
    fun `windows opener never goes through cmd and keeps the path out of the command line`() {
        val cmd = openCommand(ampersandPath, DesktopOs.WINDOWS, systemRoot = "C:\\Windows")
        assertTrue(
            cmd.argv.none { it.equals("cmd", ignoreCase = true) || it.endsWith("\\cmd.exe", ignoreCase = true) },
            "opened through cmd.exe: ${cmd.argv}",
        )
        assertTrue(cmd.argv.none { "calc" in it }, "the path reached the command line: ${cmd.argv}")
        assertEquals(listOf(ampersandPath), cmd.env.values.toList())
    }

    @Test
    fun `windows commands run System32 PowerShell, never whatever the search path finds`() {
        val expected = "D:\\Win\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        assertEquals(expected, openCommand(ampersandPath, DesktopOs.WINDOWS, systemRoot = "D:\\Win\\").argv.first())
        assertEquals(expected, windowsPowerShell(systemRoot = "D:\\Win"))
        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", windowsPowerShell(systemRoot = null))
    }

    /** `say --output-file=~/.zshrc` would overwrite the file with audio; espeak's `-w` does the same. */
    @Test
    fun `speech programs never read the text as their own options`() {
        val optionLike = "--output-file=/Users/a/.zshrc"
        assertEquals(listOf("say", "--", optionLike), speechCommand(optionLike, DesktopOs.MACOS) { false }?.argv)
        assertEquals(listOf("espeak-ng", "--", optionLike), speechCommand(optionLike, DesktopOs.LINUX) { it == "espeak-ng" }?.argv)
        assertEquals(listOf("espeak", "--", optionLike), speechCommand(optionLike, DesktopOs.LINUX) { it == "espeak" }?.argv)
    }

    /**
     * Pipe mode (`-e`) ignores the argument and reads stdin, where a line starting with `!-!` is sent
     * to speech-dispatcher as a command. `-w` keeps the process alive until the text is spoken, like
     * `say` and espeak, so stopping it still means something.
     */
    @Test
    fun `spd-say gets the text as one argument and waits for it to be spoken`() {
        assertEquals(
            listOf("spd-say", "-w", "--", "!-!SET self VOLUME 100"),
            speechCommand("!-!SET self VOLUME 100", DesktopOs.LINUX) { it == "spd-say" }?.argv,
        )
        assertEquals(null, speechCommand("hello", DesktopOs.LINUX) { false })
    }

    @Test
    fun `macOS and Linux openers take the path as one argument`() {
        assertEquals(listOf("open", "/tmp/a&b.txt"), openCommand("/tmp/a&b.txt", DesktopOs.MACOS).argv)
        assertEquals(listOf("xdg-open", "/tmp/a&b.txt"), openCommand("/tmp/a&b.txt", DesktopOs.LINUX).argv)
        assertEquals(listOf("xdg-open", "https://example.com/?a=1&b=2"), openCommand("https://example.com/?a=1&b=2", DesktopOs.LINUX).argv)
    }

    @Test
    fun `a target that would read as an option is refused`() {
        for (os in DesktopOs.entries) {
            assertFailsWith<IllegalArgumentException> { openCommand("-a Calculator", os) }
        }
    }

    @Test
    fun `start hands the child its environment and closes its stdin`() {
        assumeTrue("needs /bin/sh", DesktopOs.current != DesktopOs.WINDOWS)
        val out = Files.createTempFile("desktop-os", ".txt").toFile()
        var p: Process? = null
        try {
            // `cat` only returns once stdin is closed, so a stdin left open makes this time out.
            val cmd = OsCommand(listOf("/bin/sh", "-c", "printf %s \"\$DESKTOP_OS_TEST\"; cat"), mapOf("DESKTOP_OS_TEST" to quoteBreakout))
            p = cmd.start(ProcessBuilder.Redirect.to(out))
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the child is still waiting on stdin")
            assertEquals(quoteBreakout, out.readText())
        } finally {
            p?.destroyForcibly()
            out.delete()
        }
    }

    @Test
    fun `PowerShell speaks the text literally`() {
        val ps = powerShellHere()
        assumeTrue("no PowerShell on this machine", ps != null)
        val payloads = listOf(
            quoteBreakout,
            "don\u2019t",
            "it's \"quoted\" \$env:PATH \$(Set-Content -Path pwned -Value 2) `whoami` \u2018\u2019\u201A\u201B çğışöü & | ; % ^",
        )
        for (payload in payloads) {
            val cmd = assertNotNull(speechCommand(payload, DesktopOs.WINDOWS) { false })
            val shim = "function Add-Type {}; function New-Object { [pscustomobject]@{} | Add-Member -MemberType " +
                "ScriptMethod -Name Speak -Value { param(\$s) [IO.File]::WriteAllText(\$env:DESKTOP_OS_OUT, \$s) } -PassThru }"
            val run = runPowerShell(ps!!, cmd, shim)
            assertFalse(run.injected, "the text ran as PowerShell:\n${run.output}")
            assertEquals(payload, run.received, "PowerShell did not receive the text as data:\n${run.output}")
        }
    }

    @Test
    fun `PowerShell opens the path literally`() {
        val ps = powerShellHere()
        assumeTrue("no PowerShell on this machine", ps != null)
        val path = "C:\\Users\\a\\report&calc \u2018x\u2019 \$(Set-Content -Path pwned -Value 3) 'y'.txt"
        val cmd = openCommand(path, DesktopOs.WINDOWS)
        assertTrue(cmd.argv.first().endsWith("powershell.exe"), "not a PowerShell command: ${cmd.argv}")
        // Record what New-Object was given, then stop before anything is started.
        val shim = "function New-Object { param(\$TypeName, \$ArgumentList) " +
            "[IO.File]::WriteAllText(\$env:DESKTOP_OS_OUT, \$ArgumentList); throw 'recorded' }"
        val run = runPowerShell(ps!!, cmd, shim)
        assertFalse(run.injected, "the path ran as PowerShell:\n${run.output}")
        assertEquals(path, run.received, "PowerShell did not receive the path as data:\n${run.output}")
    }

    private class PowerShellRun(val received: String?, val injected: Boolean, val output: String)

    /** Run [cmd]'s own script, after [shim], in [ps], from a fresh directory; `pwned` there means injection. */
    private fun runPowerShell(ps: String, cmd: OsCommand, shim: String): PowerShellRun {
        val dir = Files.createTempDirectory("desktop-os-ps").toFile()
        try {
            val received = File(dir, "received.txt")
            val argv = listOf(ps) + cmd.argv.drop(1).dropLast(1) + "$shim; ${cmd.argv.last()}"
            val p = ProcessBuilder(argv).directory(dir).redirectErrorStream(true).apply {
                environment().putAll(cmd.env)
                environment()["DESKTOP_OS_OUT"] = received.path
            }.start()
            p.outputStream.close()
            val output = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "PowerShell did not exit:\n$output")
            return PowerShellRun(received.takeIf { it.exists() }?.readText(), File(dir, "pwned").exists(), output)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun powerShellHere(): String? = when (DesktopOs.current) {
        DesktopOs.WINDOWS -> windowsPowerShell()
        else -> "pwsh".takeIf { which(it) }
    }
}
