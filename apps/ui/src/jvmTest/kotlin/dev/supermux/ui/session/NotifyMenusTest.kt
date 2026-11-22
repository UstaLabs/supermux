package dev.supermux.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.NotificationOverrides
import dev.supermux.proto.NotificationPrefs
import dev.supermux.proto.NotifyLevel
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.notify.LocalNotifyControls
import dev.supermux.ui.notify.NotifyControls
import dev.supermux.ui.notify.NotifyTarget
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** #163: a row's Notifications menu replaces Mute where the broker has notification settings, and Off shows a mark. */
@OptIn(ExperimentalTestApi::class)
class NotifyMenusTest {
    private val picks = mutableListOf<Pair<NotifyTarget, NotifyLevel?>>()

    private fun model() = deriveWorkspaceRow(
        w = workspaceDto(id = "w1", name = "Fix it", views = listOf(workspaceChatView("v1", "s1", "w1")), primarySessionId = "s1"),
        sessionsById = mapOf("s1" to SessionInfo(id = "s1", name = "Fix it", workdir = "/home/u/app", agent = "claude")),
        agentState = emptyMap(),
        lastBySession = emptyMap(),
        lastRead = emptyMap(),
        home = "/home/u",
        selectedSessionId = null,
    )

    @Composable
    private fun Row(prefs: NotificationPrefs?, onToggleMute: () -> Unit = {}) {
        val ctl = prefs?.let { p ->
            NotifyControls(prefsForSession = { p }, appletPrefs = p, set = { t, l -> picks += t to l }, version = p)
        }
        CompositionLocalProvider(
            LocalInputMode provides InputMode.Touch,
            LocalContextMenuAvailable provides false,
            LocalNotifyControls provides ctl,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Column {
                    WorkspaceRow(
                        model = model(), active = false, onClick = {}, onToggleMute = onToggleMute,
                        notify = NotifyTarget("s1"),
                    )
                }
            }
        }
    }

    @Test fun an_older_broker_keeps_mute() = runComposeUiTest {
        var muted = 0
        setContent { Row(prefs = null, onToggleMute = { muted++ }) }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Mute").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, muted)
        onNodeWithTag("notify_off_mark", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun notifications_opens_its_choices_and_a_pick_is_sent_for_that_chat() = runComposeUiTest {
        setContent { Row(prefs = NotificationPrefs(sessions = NotifyLevel.Needs)) }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Mute").assertDoesNotExist()
        onNodeWithText("Notifications…").assertIsDisplayed().performClick()
        waitForIdle()
        // Default names the group's level; the levels follow.
        onNodeWithText("Default · Needs me").assertIsDisplayed()
        onNodeWithTag("notify_menu_off").performClick()
        waitForIdle()
        assertEquals<List<Pair<NotifyTarget, NotifyLevel?>>>(listOf(NotifyTarget("s1") to NotifyLevel.Off), picks)
    }

    @Test fun a_chat_set_to_off_shows_the_bell_off_mark() = runComposeUiTest {
        var prefs by mutableStateOf(NotificationPrefs())
        setContent { Row(prefs = prefs) }
        onNodeWithTag("notify_off_mark", useUnmergedTree = true).assertDoesNotExist()
        prefs = NotificationPrefs(overrides = NotificationOverrides(session = mapOf("s1" to NotifyLevel.Off)))
        waitForIdle()
        onNodeWithTag("notify_off_mark", useUnmergedTree = true).assertIsDisplayed()
    }
}
