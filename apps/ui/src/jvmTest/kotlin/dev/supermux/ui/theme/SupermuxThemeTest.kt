package dev.supermux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.supermuxDark
import dev.supermux.ui.supermuxLight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the shared theme's contract: the brand OKLCH palette drives `MaterialTheme.colorScheme`,
 * `LocalPanes` follows the appearance, and haptics degrade to a no-op with no provider.
 */
@OptIn(ExperimentalTestApi::class)
class SupermuxThemeTest {
    @Test
    fun darkSchemeComesFromTheBrandPalette() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK, typography = supermuxTypography()) {
                assertEquals(Color(supermuxDark().primary), MaterialTheme.colorScheme.primary)
                assertEquals(Color(supermuxDark().background), MaterialTheme.colorScheme.background)
                Text("dark")
            }
        }
        onNodeWithText("dark").assertIsDisplayed()
    }

    @Test
    fun lightSchemeComesFromTheBrandPalette() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.LIGHT, typography = supermuxTypography()) {
                assertEquals(Color(supermuxLight().primary), MaterialTheme.colorScheme.primary)
                assertEquals(Color(supermuxLight().background), MaterialTheme.colorScheme.background)
                Text("light")
            }
        }
        onNodeWithText("light").assertIsDisplayed()
    }

    @Test
    fun panesFollowTheAppearance() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK, typography = supermuxTypography()) {
                assertEquals(supermuxDark(), LocalPanes.current)
                Text("panes-dark")
            }
            SupermuxTheme(appearance = AppearanceMode.LIGHT, typography = supermuxTypography()) {
                assertEquals(supermuxLight(), LocalPanes.current)
                Text("panes-light")
            }
        }
        onNodeWithText("panes-dark").assertIsDisplayed()
        onNodeWithText("panes-light").assertIsDisplayed()
    }

    @Test
    fun semanticsFollowTheAppearance() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.LIGHT, typography = supermuxTypography()) {
                assertEquals(supermuxSemanticsLight(), LocalSemantics.current)
                Text("semantics-light")
            }
        }
        onNodeWithText("semantics-light").assertIsDisplayed()
    }

    @Test
    fun hapticsWithoutAProviderAreANoOp() = runComposeUiTest {
        setContent {
            val haptics = rememberHaptics()
            assertEquals(NoHaptics, haptics)
            HapticKind.entries.forEach { haptics.perform(it) } // must not throw
            Text("no-haptics")
        }
        onNodeWithText("no-haptics").assertIsDisplayed()
    }
}
