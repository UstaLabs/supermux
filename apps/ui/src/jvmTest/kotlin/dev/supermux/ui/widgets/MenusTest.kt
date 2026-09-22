package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec for the shared menu surfaces: the rows are visible only while the menu is expanded, a click
 * reaches the row's `onClick`, and the modal host is engaged only while the menu is open (a closed
 * menu is composed all over the app and must not pin the desktop's heavyweight children hidden).
 *
 * Both density branches are exercised: [InputMode.Pointer] is the compact desktop row,
 * [InputMode.Touch] is Material3's 48dp thumb row.
 */
@OptIn(ExperimentalTestApi::class)
class MenusTest {

    @Test
    fun items_are_hidden_until_expanded_then_click_invokes() = runComposeUiTest {
        var expanded by mutableStateOf(false)
        var clicks = 0
        setContent {
            Box {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { clicks++ })
                }
            }
        }
        onNodeWithText("Rename").assertDoesNotExist()
        expanded = true
        waitForIdle()
        onNodeWithText("Rename").assertIsDisplayed()
        onNodeWithText("Rename").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun touch_rows_click_too() = runComposeUiTest {
        var clicks = 0
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalPointerAvailable provides false,
            ) {
                Box {
                    DropdownMenu(expanded = true, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text("Restore") }, onClick = { clicks++ })
                    }
                }
            }
        }
        onNodeWithText("Restore").assertIsDisplayed()
        onNodeWithText("Restore").performClick()
        assertEquals(1, clicks)
    }

    /**
     * Row density follows the POINTER, not the keyboard: a phone with a Bluetooth keyboard reports
     * `InputMode.Pointer` and must still get Material3's 48dp thumb rows.
     */
    @Test
    fun row_height_follows_pointer_availability_not_input_mode() {
        // Touch, no pointer — a plain phone (or one with only a keyboard): Material3's thumb row.
        assertTrue(rowHeight(InputMode.Touch, pointer = false) >= 44.dp, "touch rows must stay thumb-sized")
        assertTrue(rowHeight(InputMode.Pointer, pointer = false) >= 44.dp, "a keyboard alone is not a pointer")
        // A mouse or trackpad is attached (tablet, DeX, desktop): the compact row is reachable.
        assertTrue(rowHeight(InputMode.Touch, pointer = true) < 40.dp, "a pointer earns the compact row")
        assertTrue(rowHeight(InputMode.Pointer, pointer = true) < 40.dp, "desktop rows are compact")
    }

    private fun rowHeight(mode: InputMode, pointer: Boolean): Dp {
        var height = 0.dp
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalInputMode provides mode,
                    LocalPointerAvailable provides pointer,
                ) {
                    Box {
                        DropdownMenu(expanded = true, onDismissRequest = {}) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {},
                                modifier = Modifier.testTag("row"),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            height = onNodeWithTag("row").getBoundsInRoot().height
        }
        return height
    }

    @Test
    fun disabled_row_does_not_invoke() = runComposeUiTest {
        var clicks = 0
        setContent {
            Box {
                DropdownMenu(expanded = true, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Text("Forget") }, onClick = { clicks++ }, enabled = false)
                }
            }
        }
        onNodeWithText("Forget").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun closed_menu_does_not_engage_the_modal_host() = runComposeUiTest {
        var hosted = 0
        var expanded by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(
                LocalModalHost provides { content -> hosted++; content() },
            ) {
                Box {
                    DropdownMenu(expanded = expanded, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {})
                    }
                }
            }
        }
        assertEquals(0, hosted, "a closed menu must not count as an open modal")
        expanded = true
        waitForIdle()
        assertTrue(hosted > 0, "an open menu must engage the modal host")
    }

}
