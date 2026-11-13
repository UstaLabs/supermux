package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ActivityEvent
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #141 (Ahmet, Android): a task row with a long title squeezed its status pill to one letter a line, so "completed"
 * ran down the right edge. At phone width the pill keeps its own width, on one line; the title gives way.
 */
@OptIn(ExperimentalTestApi::class)
class TaskCardWidthTest {
    private val long = "Wait for #136's rerun Windows job and pull its MSI line and artifact, then the same for #139's dry run"

    @Test fun aLongTaskTitleLeavesItsStatusOnOneLine() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box(Modifier.width(360.dp)) {
                    ActivityKindCard(ActivityEvent(ts = "t", kind = "task", title = long, phase = "completed", taskKind = "shell"))
                }
            }
        }
        waitForIdle()
        val pill = onNodeWithText("completed").getUnclippedBoundsInRoot()
        val row = onNodeWithTag("activity-task").getUnclippedBoundsInRoot()
        val wide = pill.right - pill.left
        val tall = pill.bottom - pill.top
        assertTrue(wide > 40.dp, "the status is $wide wide")
        assertTrue(tall < 24.dp, "the status runs $tall tall: one line, not a letter a line")
        assertTrue(pill.right <= row.right + 0.5.dp, "the status ends at ${pill.right}, past the row's ${row.right}")
    }
}
