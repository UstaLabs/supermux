package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The avatar has one branch — recognised agent → brand mark, otherwise the name's initials — and
 * honours [size] in both. Rendered under Compact/Touch and Expanded/Pointer because the two apps
 * call it from opposite ends of that range; it must look the same in both.
 */
@OptIn(ExperimentalTestApi::class)
class SessionAvatarTest {

    @Test fun unknown_agent_shows_two_initials_at_the_requested_size() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalWindowWidthClass provides WindowWidthClass.Compact,
                LocalInputMode provides InputMode.Touch,
            ) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    SessionAvatar(
                        name = "beta build",
                        agent = "aider",
                        modifier = Modifier.testTag("avatar"),
                        size = 36.dp,
                    )
                }
            }
        }
        onNodeWithText("BE").assertIsDisplayed()
        val bounds = onNodeWithTag("avatar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(36.dp, bounds.width)
        assertEquals(36.dp, bounds.height)
    }

    @Test fun known_agent_shows_the_brand_mark_at_the_requested_size() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
                LocalInputMode provides InputMode.Pointer,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    SessionAvatar(
                        name = "alpha",
                        agent = "claude",
                        modifier = Modifier.testTag("avatar"),
                        size = 40.dp,
                    )
                }
            }
        }
        val bounds = onNodeWithTag("avatar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(40.dp, bounds.width)
        assertEquals(40.dp, bounds.height)
        onNodeWithText("AL").assertDoesNotExist()
    }
}
