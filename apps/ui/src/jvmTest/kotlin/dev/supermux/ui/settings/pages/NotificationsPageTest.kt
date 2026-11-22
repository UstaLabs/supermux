package dev.supermux.ui.settings.pages

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AppletDto
import dev.supermux.proto.NotificationOverrides
import dev.supermux.proto.NotificationPrefs
import dev.supermux.proto.NotificationPrefsPatch
import dev.supermux.proto.NotifyLevel
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.chat.setPlatformContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** #163: Settings › Notifications shows the account's settings and sends each change as one patch. */
@OptIn(ExperimentalTestApi::class)
class NotificationsPageTest {
    private val sent = mutableListOf<NotificationPrefsPatch>()
    private val prefs = MutableStateFlow<NotificationPrefs?>(NotificationPrefs())
    private val actions = NotificationSettingsActions(
        prefs = prefs,
        sessions = MutableStateFlow(
            listOf(
                SessionInfo(id = "sage", name = "Sage", workdir = "/home/u", agent = "claude", role = "personal_assistant"),
                SessionInfo(id = "s1", name = "Checkout tests", workdir = "/home/u/storefront", agent = "claude", session_branch = "mux/checkout"),
                SessionInfo(id = "s2", name = "Empty cart copy", workdir = "/home/u/storefront", agent = "claude"),
            ),
        ),
        applets = MutableStateFlow(listOf(AppletDto(id = "a1", name = "issues", title = "Issues"))),
        patch = { p -> sent += p; prefs.value = p.applyTo(prefs.value!!); null },
    )

    @Test fun groups_items_and_switches_send_patches() = runComposeUiTest {
        setPlatformContent { NotificationsPage(actions) }
        onNodeWithText("Your account · on all your devices").assertIsDisplayed()
        onNodeWithTag("notify_sessions_off").performScrollTo().performClick()
        waitForIdle()
        assertEquals(NotificationPrefsPatch(sessions = NotifyLevel.Off), sent.last())
        // An agent's own level, from its pill.
        onNodeWithTag("notify_item_sage_level").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("notify_item_sage_level_needs").performClick()
        waitForIdle()
        assertEquals(mapOf("sage" to NotifyLevel.Needs), sent.last().session)
        // The applet is listed with its own pill.
        onNodeWithTag("notify_item_a1_level").performScrollTo().assertIsDisplayed()
        onNodeWithTag("notify_needs_you").performScrollTo().performClick()
        waitForIdle()
        assertEquals(false, sent.last().needsYou)
    }

    @Test fun only_sessions_with_their_own_level_are_listed_until_set_one_is_opened() = runComposeUiTest {
        prefs.value = NotificationPrefs(overrides = NotificationOverrides(session = mapOf("s1" to NotifyLevel.All)))
        setPlatformContent { NotificationsPage(actions) }
        onNodeWithTag("notify_item_s1").performScrollTo().assertIsDisplayed()
        onNodeWithTag("notify_item_s2").assertDoesNotExist()
        onNodeWithTag("notify_sessions_add").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("notify_item_s2").performScrollTo().assertIsDisplayed()
        // "Use default" clears an item's own level.
        onNodeWithTag("notify_item_s1_reset").performScrollTo().performClick()
        waitForIdle()
        assertEquals(mapOf("s1" to null), sent.last().session)
    }

    @Test fun an_existing_account_gets_the_tip_once() = runComposeUiTest {
        prefs.value = NotificationPrefs(sessions = NotifyLevel.All, showTip = true)
        setPlatformContent { NotificationsPage(actions) }
        onNodeWithTag("notify_tip_yes").performClick()
        waitForIdle()
        assertEquals(NotificationPrefsPatch(sessions = NotifyLevel.Needs, showTip = false), sent.last())
        onNodeWithTag("notify_tip").assertDoesNotExist()
    }

    @Test fun an_older_broker_says_so() = runComposeUiTest {
        prefs.value = null
        setPlatformContent { NotificationsPage(actions) }
        onNodeWithTag("notify_unavailable").assertIsDisplayed()
    }
}
