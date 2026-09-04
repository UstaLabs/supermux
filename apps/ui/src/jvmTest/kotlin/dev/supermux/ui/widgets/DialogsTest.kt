package dev.supermux.ui.widgets

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec for the shared modal surfaces. The Material3 body is common; the desktop AWT shield is
 * reached through [LocalModalHost], and the only thing a test can assert about that seam is that
 * the surfaces route through it (desktop's provider then counts the open modal).
 */
@OptIn(ExperimentalTestApi::class)
class DialogsTest {

    @Test
    fun alertDialog_renders_title_and_buttons_and_invokes_callbacks() = runComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        setContent {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Kill session?") },
                text = { Text("This terminates it immediately.") },
                confirmButton = { TextButton(onClick = { confirmed++ }) { Text("Kill") } },
                dismissButton = { TextButton(onClick = { dismissed++ }) { Text("Cancel") } },
            )
        }
        onNodeWithText("Kill session?").assertIsDisplayed()
        onNodeWithText("This terminates it immediately.").assertIsDisplayed()
        onNodeWithText("Kill").performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun alertDialog_goes_through_the_modal_host() = runComposeUiTest {
        var hosted = 0
        setContent {
            CompositionLocalProvider(LocalModalHost provides countingHost { hosted++ }) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Hosted") },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
        }
        onNodeWithText("Hosted").assertIsDisplayed()
        assertTrue(hosted > 0, "LocalModalHost must wrap the dialog")
    }

    @Test
    fun dialog_renders_content_and_goes_through_the_modal_host() = runComposeUiTest {
        var hosted = 0
        setContent {
            CompositionLocalProvider(LocalModalHost provides countingHost { hosted++ }) {
                Dialog(onDismissRequest = {}) { Text("raw dialog body") }
            }
        }
        onNodeWithText("raw dialog body").assertIsDisplayed()
        assertTrue(hosted > 0, "LocalModalHost must wrap the raw dialog")
    }

    /** The default host is the identity wrapper: content still renders with no provider. */
    @Test
    fun default_modal_host_is_identity() = runComposeUiTest {
        setContent {
            Dialog(onDismissRequest = {}) { Text("unwrapped") }
        }
        onNodeWithText("unwrapped").assertIsDisplayed()
    }

    private fun countingHost(onHost: () -> Unit): @Composable (@Composable () -> Unit) -> Unit =
        { content ->
            onHost()
            content()
        }
}
