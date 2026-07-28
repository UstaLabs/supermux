package dev.supermux.ui

import dev.supermux.proto.ActivityToolBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityBodyTest {
    @Test
    fun resolveBashParts_merges_start_and_result() {
        val parts = resolveBashParts(
            body = ActivityToolBody(kind = "bash", command = "ls -la"),
            resultBody = ActivityToolBody(kind = "bash", output = "a\nb", exitCode = 0),
            input = null,
            output = null,
            toolName = "Bash",
        )
        assertNotNull(parts)
        assertEquals("ls -la", parts.command)
        assertEquals("a\nb", parts.output)
        assertEquals(0, parts.exitCode)
    }

    @Test
    fun resolveEditParts_prefers_body_diff() {
        val parts = resolveEditParts(
            body = ActivityToolBody(kind = "edit", path = "a.ts", diff = "@@\n-a\n+b", oldText = "a", newText = "b"),
            input = null,
            toolName = "Edit",
        )
        assertNotNull(parts)
        assertEquals("a.ts", parts.path)
        assertTrue(parts.diff!!.contains("-a"))
    }

    @Test
    fun resolveEditParts_write_becomes_add_lines() {
        val parts = resolveEditParts(
            body = ActivityToolBody(kind = "write", path = "n.ts", content = "x"),
            input = null,
            toolName = "Write",
        )
        assertNotNull(parts)
        assertEquals("add", parts.mode)
        assertEquals("+x", parts.diff)
    }

    @Test
    fun resolveBashParts_null_for_non_bash() {
        assertNull(
            resolveBashParts(
                body = ActivityToolBody(kind = "generic", input = "x"),
                resultBody = null,
                input = "x",
                output = null,
                toolName = "Read",
            ),
        )
    }
}
