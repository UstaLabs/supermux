package dev.supermux.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class SemanticColorsTest {
    @Test
    fun darkAndLightDiffer() {
        assertNotEquals(supermuxSemanticsDark().success, supermuxSemanticsLight().success)
    }

    @Test
    fun theLocalDefaultsToTheDarkSet() = runComposeUiTest {
        setContent {
            assertEquals(supermuxSemanticsDark(), LocalSemantics.current)
            Text("semantics")
        }
        onNodeWithText("semantics").assertIsDisplayed()
    }
}
