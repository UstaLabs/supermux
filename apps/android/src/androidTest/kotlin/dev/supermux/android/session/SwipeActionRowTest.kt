package dev.supermux.android.session

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwipeActionRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipeOnlyRevealsAndButtonTapExecutes() {
        var calls = 0
        var openId by mutableStateOf<String?>(null)
        compose.setContent {
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

        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Mute").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun openingSecondRowClosesFirstRow() {
        var openId by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                Column {
                    SwipeActionRow(
                        rowId = "one",
                        openRowId = openId,
                        onOpenRowChange = { openId = it },
                        startLabel = "Mute one",
                        endLabel = null,
                        onStartAction = {},
                        onEndAction = {},
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(Color.Black)
                                .testTag("row-one"),
                        )
                    }
                    SwipeActionRow(
                        rowId = "two",
                        openRowId = openId,
                        onOpenRowChange = { openId = it },
                        startLabel = "Mute two",
                        endLabel = null,
                        onStartAction = {},
                        onEndAction = {},
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .background(Color.Black)
                                .testTag("row-two"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("row-one").performTouchInput { swipeRight() }
        compose.onNodeWithText("Mute one").assertIsDisplayed()
        compose.onNodeWithTag("row-two").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Mute one").assertDoesNotExist()
        compose.onNodeWithText("Mute two").assertIsDisplayed()
    }
}
