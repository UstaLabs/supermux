package dev.supermux.desktop.platform

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * OsCommand is the desktop app's one way to start a process (#29 step 9): the child broker, the sleep inhibitor, the
 * bundled broker's `version` and the quiet helpers all go through it. What they need: a whole environment of their
 * own, a working directory, and stderr kept apart from the stdout that is read.
 */
class OsCommandTest {
    private fun posix() = assumeTrue("POSIX sh only", DesktopOs.current != DesktopOs.WINDOWS)

    @Test fun aReplacedEnvironmentIsTheChildsWholeEnvironmentAndItRunsInItsFolder() {
        posix()
        val dir = createTempDirectory("oscommand-").toRealPath()
        val command = OsCommand(
            listOf("/bin/sh", "-c", "printf '%s|%s|%s' \"\$FOO\" \"\${HOME:-unset}\" \"\$(pwd -P)\""),
            env = mapOf("FOO" to "bar"),
            replaceEnv = true,
            workDir = dir,
        )
        val p = command.start(output = ProcessBuilder.Redirect.PIPE)
        assertEquals("bar|unset|$dir", p.inputStream.bufferedReader().readText())
        p.waitFor()
        Files.deleteIfExists(dir)
    }

    @Test fun stderrCanBeKeptOutOfTheStdoutThatIsRead() {
        posix()
        val argv = listOf("/bin/sh", "-c", "printf out; printf err >&2")
        val apart = OsCommand(argv).start(output = ProcessBuilder.Redirect.PIPE, errors = ProcessBuilder.Redirect.DISCARD)
        assertEquals("out", apart.inputStream.bufferedReader().readText())
        val merged = OsCommand(argv).start(output = ProcessBuilder.Redirect.PIPE)
        assertEquals(setOf("out", "err"), merged.inputStream.bufferedReader().readText().chunked(3).toSet())
    }
}
