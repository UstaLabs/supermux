package dev.supermux.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.chat.ToolStatus
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import kotlin.test.Test

/**
 * High detail's Bash and Edit/Write panes are the native editor, read-only: the command, its
 * output, a written file and an edit's diff are the editor's text, a long one capped at
 * [TOOL_PANE_LINES] lines with a "Show all" toggle.
 */
@OptIn(ExperimentalTestApi::class)
class ToolPaneEditorTest {

    private fun bash(output: String) = ActivityEvent(
        ts = "2026-10-01T00:00:00Z", kind = "tool", tool = "Bash",
        body = ActivityToolBody(kind = "bash", command = "ls -la", output = output, exitCode = 0),
    )

    @Test fun bash_commandAndOutput_areEditors() = runComposeUiTest {
        setPlatformContent { ToolCard(bash("a\nb"), ToolStatus.DONE, highDetail = true) }
        onNodeWithContentDescription("Command").assert(hasText("ls -la"))
        onNodeWithContentDescription("Output").assert(hasText("a\nb"))
        // Two lines of 12sp × 1.5 at density 1, no toggle.
        onNodeWithTag("tool_terminal_output").assertHeightIsEqualTo((2 * 18).dp)
        onNodeWithTag("tool_terminal_output_toggle").assertDoesNotExist()
    }

    @Test fun longOutput_isCapped_untilShowAll() = runComposeUiTest {
        val output = (1..30).joinToString("\n") { "line $it" }
        setPlatformContent { ToolCard(bash(output), ToolStatus.DONE, highDetail = true) }
        onNodeWithTag("tool_terminal_output").assertHeightIsEqualTo((TOOL_PANE_LINES * 18).dp)
        onNodeWithText("Show all 30 lines").performClick()
        onNodeWithTag("tool_terminal_output").assertHeightIsEqualTo((30 * 18).dp)
        onNodeWithText("Show less").assertExists()
    }

    @Test fun edit_isItsDiffAsADocument() = runComposeUiTest {
        val event = ActivityEvent(
            ts = "2026-10-01T00:00:00Z", kind = "tool", tool = "Edit",
            body = ActivityToolBody(kind = "edit", path = "src/a.kt", diff = "--- a/src/a.kt\n+++ b/src/a.kt\n@@ -1,1 +1,1 @@\n-val a = 1\n+val a = 2"),
        )
        setPlatformContent { ToolCard(event, ToolStatus.DONE, highDetail = true) }
        onNodeWithContentDescription("a.kt").assert(hasText("val a = 1\nval a = 2"))
        onNodeWithTag("tool_diff_editor").assertHeightIsEqualTo((2 * 18).dp)
    }

    @Test fun write_isTheFileContent_withoutPlusPrefixes() = runComposeUiTest {
        val event = ActivityEvent(
            ts = "2026-10-01T00:00:00Z", kind = "tool", tool = "Write",
            body = ActivityToolBody(kind = "write", path = "notes.md", content = "# Title\nbody"),
        )
        setPlatformContent { ToolCard(event, ToolStatus.DONE, highDetail = true) }
        onNodeWithContentDescription("notes.md").assert(hasText("# Title\nbody"))
    }
}
