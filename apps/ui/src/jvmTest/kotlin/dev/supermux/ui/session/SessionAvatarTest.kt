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
 * The avatar has two branches that must BOTH survive: recognised agent → brand mark, otherwise the
 * name's initials; and Touch → Android's tiled look, Pointer → desktop's bare mark. The tile is the
 * one that keeps a black mark like Cursor's legible on a phone, so its presence under
 * [InputMode.Touch] and absence under [InputMode.Pointer] is pinned here.
 */
@OptIn(ExperimentalTestApi::class)
class SessionAvatarTest {

    @Test fun touch_puts_the_brand_mark_on_a_tile() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalWindowWidthClass provides WindowWidthClass.Compact,
                LocalInputMode provides InputMode.Touch,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    SessionAvatar(
                        name = "alpha",
                        agent = "cursor",
                        modifier = Modifier.testTag("avatar"),
                        size = 40.dp,
                    )
                }
            }
        }
        onNodeWithTag(AGENT_LOGO_TILE_TAG, useUnmergedTree = true).assertIsDisplayed()
        val bounds = onNodeWithTag("avatar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(40.dp, bounds.width)
        assertEquals(40.dp, bounds.height)
    }

    @Test fun pointer_shows_the_bare_mark_with_no_tile() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
                LocalInputMode provides InputMode.Pointer,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    SessionAvatar(
                        name = "alpha",
                        agent = "cursor",
                        modifier = Modifier.testTag("avatar"),
                        size = 40.dp,
                    )
                }
            }
        }
        onNodeWithTag(AGENT_LOGO_TILE_TAG, useUnmergedTree = true).assertDoesNotExist()
        val bounds = onNodeWithTag("avatar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(40.dp, bounds.width)
        assertEquals(40.dp, bounds.height)
    }

    @Test fun unknown_agent_shows_two_initials_at_the_requested_size_in_both_modes() {
        for (mode in listOf(InputMode.Touch, InputMode.Pointer)) {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalInputMode provides mode) {
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
                onNodeWithTag(AGENT_LOGO_TILE_TAG, useUnmergedTree = true).assertDoesNotExist()
                val bounds = onNodeWithTag("avatar", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertEquals(36.dp, bounds.width, "initials tile size under $mode")
                assertEquals(36.dp, bounds.height, "initials tile size under $mode")
            }
        }
    }
}
