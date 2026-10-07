package dev.supermux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.ProvideWindowWidthClass
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

    // --- typography selection (task A3) ------------------------------------------------
    // With no explicit `typography`, the theme picks the touch scale for a Compact window and
    // the desktop scale for Medium/Expanded ("desktop wins for medium/expanded", spec
    // §Foundations). The rule is width-only: a keyboard paired to a phone must not shrink type.

    @Test
    fun compactGetsTheTouchTypeScaleEvenWithAPointer() = runComposeUiTest {
        setContent {
            val expected = supermuxTouchTypography().headlineSmall.fontSize
            ProvideWindowWidthClass(widthDp = 411) {
                // Phone with a Bluetooth keyboard/mouse: still the touch scale.
                CompositionLocalProvider(LocalInputMode provides InputMode.Pointer) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        assertEquals(expected, MaterialTheme.typography.headlineSmall.fontSize)
                        Text("touch-scale")
                    }
                }
            }
        }
        onNodeWithText("touch-scale").assertIsDisplayed()
    }

    @Test
    fun expandedPointerGetsTheDesktopTypeScale() = runComposeUiTest {
        setContent {
            val expected = supermuxTypography().headlineSmall.fontSize
            ProvideWindowWidthClass(widthDp = 1440) {
                CompositionLocalProvider(LocalInputMode provides InputMode.Pointer) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        assertEquals(expected, MaterialTheme.typography.headlineSmall.fontSize)
                        Text("desktop-scale")
                    }
                }
            }
        }
        onNodeWithText("desktop-scale").assertIsDisplayed()
    }

    @Test
    fun mediumTouchGetsTheDesktopTypeScale() = runComposeUiTest {
        setContent {
            val expected = supermuxTypography().headlineSmall.fontSize
            // Android tablet / unfolded foldable: touch, but Medium — desktop wins.
            ProvideWindowWidthClass(widthDp = 800) {
                CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        assertEquals(expected, MaterialTheme.typography.headlineSmall.fontSize)
                        Text("tablet-scale")
                    }
                }
            }
        }
        onNodeWithText("tablet-scale").assertIsDisplayed()
    }
}
