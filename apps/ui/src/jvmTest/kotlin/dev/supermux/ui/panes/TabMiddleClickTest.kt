package dev.supermux.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** A middle click on a tab closes it, as in a browser. */
@OptIn(ExperimentalTestApi::class)
class TabMiddleClickTest {
    @Test fun aMiddleClickClosesTheTab() = runComposeUiTest {
        val closed = mutableListOf<String>()
        setContent {
            Box(Modifier.size(200.dp, 28.dp).testTag("chip")) {
                DefaultTabChip("v1", "zsh", TabSlotState(selected = false, dragged = false), FontFamily.Default, onClose = { closed += it })
            }
        }
        onNodeWithTag("chip").performMouseInput { press(MouseButton.Tertiary); release(MouseButton.Tertiary) }
        waitForIdle()
        assertEquals(listOf("v1"), closed)
    }

    @Test fun aPrimaryClickDoesNot() = runComposeUiTest {
        val closed = mutableListOf<String>()
        setContent {
            Box(Modifier.size(200.dp, 28.dp).testTag("chip")) {
                DefaultTabChip("v1", "zsh", TabSlotState(selected = false, dragged = false), FontFamily.Default, onClose = { closed += it })
            }
        }
        onNodeWithTag("chip").performMouseInput { press(MouseButton.Primary); release(MouseButton.Primary) }
        waitForIdle()
        assertEquals(emptyList(), closed)
    }
}
