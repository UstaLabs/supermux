package dev.supermux.desktop.packaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebLauncherEntryTest {
    // jpackage's entry for the .deb, as it ships today.
    private val jpackageEntry = """
        [Desktop Entry]
        Name=supermux
        Comment=supermux desktop
        Exec=/opt/supermux/bin/supermux
        Icon=/opt/supermux/lib/supermux.png
        Terminal=false
        Type=Application
        Categories=Development
        MimeType=
    """.trimIndent() + "\n"

    @Test
    fun startupWmClassGoesRightAfterType() {
        val out = DebLauncherEntry.withStartupWmClass(jpackageEntry, "supermux")
        val lines = out.lines()
        assertEquals("StartupWMClass=supermux", lines[lines.indexOf("Type=Application") + 1])
        assertTrue(DebLauncherEntry.hasStartupWmClass(out))
        assertTrue(out.endsWith("MimeType=\n"))
        assertEquals(jpackageEntry.lines().size + 1, lines.size)
    }

    @Test
    fun aReRunChangesNothing() {
        val once = DebLauncherEntry.withStartupWmClass(jpackageEntry, "supermux")
        assertEquals(once, DebLauncherEntry.withStartupWmClass(once, "supermux"))
        assertEquals(1, once.lines().count { it.startsWith("StartupWMClass=") })
    }

    @Test
    fun anExistingStartupWmClassIsKept() {
        val custom = jpackageEntry.replace("Type=Application\n", "Type=Application\nStartupWMClass=other\n")
        assertEquals(custom, DebLauncherEntry.withStartupWmClass(custom, "supermux"))
    }

    @Test
    fun withoutATypeLineItGoesAtTheEnd() {
        val out = DebLauncherEntry.withStartupWmClass("[Desktop Entry]\nName=x\n", "supermux")
        assertEquals("[Desktop Entry]\nName=x\nStartupWMClass=supermux\n", out)
    }

    @Test
    fun hasStartupWmClassReadsWholeLinesOnly() {
        assertFalse(DebLauncherEntry.hasStartupWmClass(jpackageEntry))
        assertFalse(DebLauncherEntry.hasStartupWmClass("Comment=see StartupWMClass=x\n"))
    }

    @Test
    fun md5sumsGetTheEditedFilesNewSum() {
        val sums = "aaaa  opt/supermux/bin/supermux\nbbbb  opt/supermux/lib/supermux-supermux.desktop\n"
        val out = DebLauncherEntry.withMd5(sums, "opt/supermux/lib/supermux-supermux.desktop", "cccc")
        assertEquals("aaaa  opt/supermux/bin/supermux\ncccc  opt/supermux/lib/supermux-supermux.desktop\n", out)
        assertEquals(out, DebLauncherEntry.withMd5(out, "opt/supermux/lib/supermux-supermux.desktop", "cccc"), "idempotent")
        assertEquals(sums, DebLauncherEntry.withMd5(sums, "opt/not/listed", "dddd"), "an unlisted path changes nothing")
    }
}
