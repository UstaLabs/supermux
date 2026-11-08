package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * A fake executable that stays running for [seconds] through a CHILD process, so a test can check
 * that a kill reaches the whole tree. It is a `/bin/sh` script running `sleep` on macOS/Linux and a
 * `.cmd` running `ping` on Windows (the release workflow runs these tests on Windows too, and a
 * `#!/bin/sh` script is not executable there).
 */
fun slowExecutable(seconds: Int): Path =
    if (posixHost) {
        Files.createTempFile("slow-broker", ".sh").also {
            Files.writeString(it, "#!/bin/sh\nsleep $seconds\n")
            it.toFile().setExecutable(true)
        }
    } else {
        Files.createTempFile("slow-broker", ".cmd").also {
            // ping waits about a second between echoes; -n N runs for roughly N-1 seconds.
            Files.writeString(it, "@echo off\r\nping -n ${seconds + 1} 127.0.0.1 >nul\r\n")
        }
    }

/**
 * True once [p]'s tree holds the command [slowExecutable]'s script waits in (`sleep`, or `ping` on Windows): from then
 * on, killing the tree reaches everything that holds its stdout. Any descendant is not enough on Windows, where a
 * console script's first descendant is its conhost.exe, there before the script has started anything; a `ping` it
 * starts after a kill's snapshot outlives the kill and keeps the pipe open.
 */
fun hasSlowChild(p: Process): Boolean {
    val slow = if (posixHost) "sleep" else "ping"
    return p.descendants().anyMatch { d ->
        d.info().command().map { c -> Path.of(c).fileName.toString().lowercase().substringBefore('.') == slow }.orElse(false)
    }
}
