package dev.supermux.desktop.platform

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A command found on PATH in-process. `which` was started as a process: Windows has no which.exe, so read-aloud never
 * found ffplay or mpv there; some Linux base images lack it too, and systemctl then read as absent.
 */
class PathLookupTest {
    private val root: Path = Files.createTempDirectory("path-lookup")
    private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun file(dir: String, name: String, executable: Boolean = true): Path {
        val d = Files.createDirectories(root.resolve(dir))
        val f = Files.writeString(d.resolve(name), "x")
        if (posix) Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(if (executable) "rwx------" else "rw-------"))
        return f
    }

    private fun path(vararg dirs: String, sep: String) = dirs.joinToString(sep) { root.resolve(it).toString() }

    @Test
    fun `on Windows a bare name gets an extension from PATHEXT, the first folder of PATH that has one wins`() {
        file("a", "ffplay.txt")
        val exe = file("b", "ffplay.exe", executable = false) // Windows has no executable bit
        file("c", "ffplay.exe")
        assertEquals(exe, findOnPath("ffplay", path("a", "b", "c", sep = ";"), windows = true, pathExt = ".COM;.EXE;.BAT;.CMD"))
        val cmd = file("d", "npx.cmd", executable = false)
        assertEquals(cmd, findOnPath("npx", path("d", sep = ";"), windows = true, pathExt = null))
        assertEquals(exe, findOnPath("ffplay.exe", path("b", sep = ";"), windows = true, pathExt = ".EXE"))
    }

    @Test
    fun `a relative or empty PATH entry is never searched, so the current folder cannot plant a command`() {
        assertNull(findOnPath("ffplay", ";.;relative", windows = true, pathExt = ".EXE"))
        assertNull(findOnPath("ffplay", "::.:relative", windows = false, pathExt = null))
        assertNull(findOnPath("ffplay", null, windows = false, pathExt = null))
    }

    @Test
    fun `elsewhere a command is an executable file found in a colon-separated PATH`() {
        if (!posix) return
        file("a", "mpv", executable = false)
        val mpv = file("b", "mpv")
        assertEquals(mpv, findOnPath("mpv", path("a", "b", sep = ":"), windows = false, pathExt = null))
        assertNull(findOnPath("mpv.exe", path("a", "b", sep = ":"), windows = false, pathExt = null))
    }
}
