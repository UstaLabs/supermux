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
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
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
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
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
