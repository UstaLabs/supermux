package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkdirPathTest {
    private val workdir = "/home/user/projects/project-api"
    private val home = "/home/user"

    @Test fun relative_pass_through() {
        assertEquals("src/main.ts", toWorkdirRelativePath("src/main.ts", workdir, home))
        assertEquals("src/main.ts", toWorkdirRelativePath("./src/main.ts", workdir, home))
    }

    @Test fun strips_absolute_under_workdir() =
        assertEquals("src/main.ts", toWorkdirRelativePath("$workdir/src/main.ts", workdir, home))

    @Test fun strips_home_relative_under_workdir() =
        assertEquals(
            "src/main.ts",
            toWorkdirRelativePath("~/projects/project-api/src/main.ts", workdir, home),
        )

    @Test fun rejects_outside_workdir() =
        assertNull(toWorkdirRelativePath("/etc/passwd", workdir, home))

    @Test fun strips_single_line_suffix() =
        assertEquals("src/a.ts", toWorkdirRelativePath("src/a.ts:10", workdir, home))

    @Test fun strips_range_suffix_absolute() =
        assertEquals("src/a.ts", toWorkdirRelativePath("$workdir/src/a.ts:10-20", workdir, home))

    // When homeDir is null AND the path is tilde-relative, home cannot be inferred
    // (inferHomeDir only recognizes "/home|/Users" absolute paths, not "~/..."), so the
    // "~" can't be expanded and the path stays unresolved → null. Verified against the
    // live web toWorkdirRelativePath, which returns null here too. (The plan's expected
    // "src/a.ts" is wrong; real callers always pass inferHomeDir(workdir), never null.)
    @Test fun tilde_path_unresolved_when_home_null() =
        assertNull(toWorkdirRelativePath("~/projects/project-api/src/a.ts", workdir, null))

    @Test fun workdir_root_itself() =
        assertEquals("", toWorkdirRelativePath(workdir, workdir, home))

    @Test fun editor_path_is_relative_inside_the_workdir() {
        assertEquals("src/main.ts", toEditorPath("src/main.ts", workdir, home))
        assertEquals("src/main.ts", toEditorPath("$workdir/src/main.ts:12", workdir, home))
        assertEquals("a.ts", toEditorPath("src/../a.ts", workdir, home))
        assertNull(toEditorPath(workdir, workdir, home))
    }

    @Test fun editor_path_is_absolute_outside_the_workdir() {
        assertEquals("/etc/passwd", toEditorPath("/etc/passwd", workdir, home))
        assertEquals("/home/user/.bashrc", toEditorPath("~/.bashrc:3", workdir, home))
        assertEquals("/home/user/projects/other/x.kt", toEditorPath("../other/x.kt", workdir, home))
        assertEquals("/tmp/b", toEditorPath("/tmp/a/../b", workdir, home))
        assertEquals(true, isAbsoluteEditorPath(toEditorPath("/etc/hosts", workdir, home)!!))
    }
}
