package dev.supermux.desktop.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import kotlin.test.Test

/**
 * UI tests for the leading session-list rail: working spinner vs unread green vs idle gray.
 * Mirrors product priority encoded in shared [dev.supermux.session.sessionListRailIndicator].
 */
@OptIn(ExperimentalTestApi::class)
class SessionStatusRailTest {

    @Test fun idle_unread_shows_unread_icon_not_working_or_neutral() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionStatusRail(git = null, working = false, unread = true)
            }
        }
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("session_rail_neutral", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun working_shows_spinner_and_hides_unread_even_when_flag_true() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionStatusRail(git = null, working = true, unread = true)
            }
        }
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("working", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("session_rail_neutral", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun idle_read_shows_neutral_gray_dot() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionStatusRail(git = null, working = false, unread = false)
            }
        }
        onNodeWithTag("session_rail_neutral", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("idle", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("session_rail_unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("session_rail_working", useUnmergedTree = true).assertDoesNotExist()
    }
}
