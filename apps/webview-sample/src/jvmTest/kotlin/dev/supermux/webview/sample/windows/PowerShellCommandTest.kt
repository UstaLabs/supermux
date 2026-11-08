package dev.supermux.webview.sample.windows

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerShellCommandTest {
    @Test
    fun aScriptsQuotesSurviveTheWindowsCommandLine() {
        // Java on Windows quotes an argument with spaces but passes a double quote inside it as nothing: the checks'
        // process query lost its filter's quotes, and PowerShell answered with an error and no process.
        val script = "Get-CimInstance Win32_Process -Filter \"Name = 'msedgewebview2.exe'\" | ForEach-Object { \$_.ProcessId }"
        val command = powershellCommand(script)
        assertEquals(listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand"), command.dropLast(1))
        assertTrue(command.last().all { it.isLetterOrDigit() || it in "+/=" }, "nothing a command line could change: ${command.last()}")
        assertEquals(script, String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE))
    }
}
