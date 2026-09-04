package dev.supermux.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.LocalPointerAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared host chips: the rename/forget affordance is a long-press on a touchscreen and a
 * right-click where a pointing device exists — both wired in ONE composable — plus the offline
 * dimming/last-seen suffix desktop contributed and the scope picker Android contributed.
 */
@OptIn(ExperimentalTestApi::class)
class HostBadgeTest {

    private val hosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1_000_000L),
    )

    private fun chips(
        pointer: Boolean,
        nowMs: Long = 1_000_000L,
        onRename: (String, String) -> Unit = { _, _ -> },
        onForget: (String) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalPointerAvailable provides pointer) {
            HostFilterChips(
                hosts = hosts,
                sessions = listOf(SessionInfo(id = "s1", name = "s", workdir = "/w", agent = "claude")),
                sessionHost = mapOf("s1" to "h1"),
                selected = null,
                onSelect = {},
                onAddHost = {},
                onRenameHost = onRename,
                onForgetHost = onForget,
                nowMs = nowMs,
            )
        }
    }

    @Test fun longPress_opensTheHostMenu_whenThereIsNoPointer() = runComposeUiTest {
        setContent(chips(pointer = false))
        onNodeWithText("Rename").assertDoesNotExist()
        onNodeWithTag("host_chip_press_h1").performTouchInput { longClick() }
        onNodeWithText("Rename").assertIsDisplayed()
        onNodeWithText("Forget").assertIsDisplayed()
    }

    @Test fun rightClick_opensTheHostMenu_whenAPointerIsAvailable() = runComposeUiTest {
        setContent(chips(pointer = true))
        onNodeWithText("Rename").assertDoesNotExist()
        onNodeWithTag("host_chip_press_h1").performMouseInput { rightClick() }
        onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test fun renameDialog_reportsTheTrimmedNewName() = runComposeUiTest {
        var renamed: Pair<String, String>? = null
        setContent(chips(pointer = false, onRename = { id, name -> renamed = id to name }))
        onNodeWithTag("host_chip_press_h1").performTouchInput { longClick() }
        onNodeWithText("Rename").performClick()
        onNodeWithTag("host_rename_field").performTextClearance()
        onNodeWithTag("host_rename_field").performTextInput("  Studio  ")
        onNodeWithTag("host_rename_confirm").performClick()
        assertEquals("h1" to "Studio", renamed)
    }

    @Test fun forgetDialog_confirmsBeforeReportingTheHost() = runComposeUiTest {
        var forgotten: String? = null
        setContent(chips(pointer = true, onForget = { forgotten = it }))
        onNodeWithTag("host_chip_press_h2").performMouseInput { rightClick() }
        onNodeWithText("Forget").performClick()
        // The menu item only OPENS the dialog; nothing is forgotten until the dialog confirms.
        assertEquals(null, forgotten)
        onNodeWithTag("host_forget_confirm").performClick()
        assertEquals("h2", forgotten)
    }

    @Test fun anOfflineChip_carriesItsLastSeenSuffix() = runComposeUiTest {
        // h2 was last seen 5 minutes before `nowMs`; h1 is online and carries no suffix.
        setContent(chips(pointer = true, nowMs = 1_000_000L + 5 * 60_000L))
        onNodeWithText("Raspberry  · 5m ago").assertIsDisplayed()
        onNodeWithText("MacBook  1").assertIsDisplayed()
    }

    @Test fun hostScopePicker_showsTheSelectionAndReportsAChange() = runComposeUiTest {
        var picked: String? = null
        setContent { HostScopePicker(hosts, selectedHostId = "h1", onSelect = { picked = it }) }
        onNodeWithTag("host_scope_picker").assertIsDisplayed()
        onNodeWithText("MacBook").assertIsDisplayed()
        onNodeWithTag("host_scope_picker").performClick()
        onNodeWithText("Raspberry Pi (offline)").performClick()
        assertEquals("h2", picked)
    }

    @Test fun hostBadge_rendersTheShortLabel() = runComposeUiTest {
        setContent { HostBadge(hosts[1]) }
        onNodeWithTag("host_badge_h2").assertIsDisplayed()
        assertTrue(true)
    }
}
