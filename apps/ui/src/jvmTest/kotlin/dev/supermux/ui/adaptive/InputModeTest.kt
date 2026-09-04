package dev.supermux.ui.adaptive

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `LocalInputMode` defaults to `Pointer` (desktop) and is overridable by the entry point. */
@OptIn(ExperimentalTestApi::class)
class InputModeTest {
    @Test
    fun defaultsToPointerWhenUnprovided() = runComposeUiTest {
        setContent {
            assertEquals(InputMode.Pointer, LocalInputMode.current)
            Text("default-pointer")
        }
        onNodeWithText("default-pointer").assertIsDisplayed()
    }

    @Test
    fun providerWins() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
                assertEquals(InputMode.Touch, LocalInputMode.current)
                Text("provided-touch")
            }
        }
        onNodeWithText("provided-touch").assertIsDisplayed()
    }
}
