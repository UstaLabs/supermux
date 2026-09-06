package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Was Android's instrumented `SwipeActionRowTest`; the same two cases now run headless on
 * `:ui`'s JVM target with real `performTouchInput` swipes, plus the Pointer-mode absence case.
 */
@OptIn(ExperimentalTestApi::class)
class SwipeActionRowTest {

    @Test fun swipeOnlyRevealsAndButtonTapExecutes() = runComposeUiTest {
        var calls = 0
        var openId by mutableStateOf<String?>(null)
        setContent {
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
                MaterialTheme {
                    SwipeActionRow(
                        rowId = "one",
                        openRowId = openId,
                        onOpenRowChange = { openId = it },
                        startLabel = "Mute",
                        endLabel = "Settle",
                        onStartAction = { calls++ },
                        onEndAction = { calls++ },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(Color.Black)
                                .testTag("row"),
                        )
                    }
                }
            }
        }

        onNodeWithTag("row").performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(0, calls) // the swipe only REVEALS; nothing fires until the button is tapped
        onNodeWithText("Mute").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, calls)
    }

    @Test fun openingSecondRowClosesFirstRow() = runComposeUiTest {
        var openId by mutableStateOf<String?>(null)
        setContent {
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
                MaterialTheme {
                    Column {
                        TwoRows(openId, { openId = it })
                    }
                }
            }
        }

        onNodeWithTag("row-one").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithText("Mute one").assertIsDisplayed()
        onNodeWithTag("row-two").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithText("Mute one").assertDoesNotExist()
        onNodeWithText("Mute two").assertIsDisplayed()
    }

    @Test fun pointerModeRendersTheContentAndNoSwipeActions() = runComposeUiTest {
        var openId by mutableStateOf<String?>(null)
        setContent {
            CompositionLocalProvider(LocalInputMode provides InputMode.Pointer) {
                MaterialTheme {
                    Column {
                        TwoRows(openId, { openId = it })
                    }
                }
            }
        }

        onNodeWithTag("row-one").assertIsDisplayed()
        // Even a full swipe reveals nothing: a mouse reaches these through the row's context menu.
        onNodeWithTag("row-one").performTouchInput { swipeRight() }
        waitForIdle()
        onNodeWithText("Mute one").assertDoesNotExist()
        assertEquals(null, openId)
    }
}

@androidx.compose.runtime.Composable
private fun TwoRows(openId: String?, onOpen: (String?) -> Unit) {
    for ((id, label) in listOf("one" to "Mute one", "two" to "Mute two")) {
        SwipeActionRow(
            rowId = id,
            openRowId = openId,
            onOpenRowChange = onOpen,
            startLabel = label,
            endLabel = null,
            onStartAction = {},
            onEndAction = {},
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(Color.Black)
                    .testTag("row-$id"),
            )
        }
    }
}
