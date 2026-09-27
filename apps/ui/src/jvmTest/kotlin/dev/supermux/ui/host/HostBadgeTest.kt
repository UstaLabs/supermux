package dev.supermux.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared host UI: the footer [HostSwitcher] (scope, rename/forget, add), the offline
 * last-seen suffix desktop contributed, and the scope picker Android contributed.
 */
@OptIn(ExperimentalTestApi::class)
class HostBadgeTest {

    private val hosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1_000_000L),
    )

    private fun switcher(
        selected: String? = null,
        hostList: List<HostView> = hosts,
        nowMs: Long = 1_000_000L,
        onSelect: (String?) -> Unit = {},
        onAdd: () -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        onForget: (String) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        HostSwitcher(
            hosts = hostList,
            sessions = listOf(SessionInfo(id = "s1", name = "s", workdir = "/w", agent = "claude")),
            sessionHost = mapOf("s1" to "h1"),
            selected = selected,
            onSelect = onSelect,
            onAddHost = onAdd,
            onRenameHost = onRename,
            onForgetHost = onForget,
            nowMs = nowMs,
        )
    }

    @Test fun switcher_namesAllHostsWithoutAFilter_andTheHostWithOne() = runComposeUiTest {
        var selected by mutableStateOf<String?>(null)
        setContent { switcher(selected = selected)() }
        onNodeWithText("All hosts").assertIsDisplayed()
        selected = "h2"
        onNodeWithText("Raspberry Pi").assertIsDisplayed()
    }

    @Test fun switcher_showsForASingleHost_soItCanAddTheNext() = runComposeUiTest {
        var added = false
        setContent(switcher(hostList = hosts.take(1), onAdd = { added = true }))
        onNodeWithTag("host_switcher").assertIsDisplayed()
        onNodeWithText("MacBook").assertIsDisplayed()
        onNodeWithTag("host_switcher").performClick()
        // One host: nothing to filter between, so no "All hosts" row.
        onNodeWithTag("host_switcher_all").assertDoesNotExist()
        onNodeWithTag("host_switcher_add").performClick()
        assertTrue(added)
    }

    @Test fun switcher_emptyFleet_drawsNothing() = runComposeUiTest {
        setContent(switcher(hostList = emptyList()))
        onNodeWithTag("host_switcher").assertDoesNotExist()
    }

    @Test fun switcher_reportsAPickedHostAndAll() = runComposeUiTest {
        var picked: String? = "sentinel"
        setContent(switcher(selected = "h1", onSelect = { picked = it }))
        onNodeWithTag("host_switcher").performClick()
        onNodeWithTag("host_switcher_h2").performClick()
        assertEquals("h2", picked)
        onNodeWithTag("host_switcher").performClick()
        onNodeWithTag("host_switcher_all").performClick()
        assertNull(picked)
    }

    @Test fun switcher_aStaleFilterReadsAsAllHosts() = runComposeUiTest {
        setContent(switcher(selected = "gone"))
        onNodeWithText("All hosts").assertIsDisplayed()
    }

    @Test fun switcher_offersRenameAndForgetOnlyForTheSelectedHost() = runComposeUiTest {
        setContent(switcher(selected = null))
        onNodeWithTag("host_switcher").performClick()
        onNodeWithTag("host_switcher_rename").assertDoesNotExist()
        onNodeWithTag("host_switcher_forget").assertDoesNotExist()
    }

    @Test fun renameDialog_reportsTheTrimmedNewName() = runComposeUiTest {
        var renamed: Pair<String, String>? = null
        setContent(switcher(selected = "h1", onRename = { id, name -> renamed = id to name }))
        onNodeWithTag("host_switcher").performClick()
        onNodeWithText("Rename MacBook…").performClick()
        onNodeWithTag("host_rename_field").performTextClearance()
        onNodeWithTag("host_rename_field").performTextInput("  Studio  ")
        onNodeWithTag("host_rename_confirm").performClick()
        assertEquals("h1" to "Studio", renamed)
    }

    @Test fun forgetDialog_confirmsBeforeReportingTheHost() = runComposeUiTest {
        var forgotten: String? = null
        setContent(switcher(selected = "h2", onForget = { forgotten = it }))
        onNodeWithTag("host_switcher").performClick()
        onNodeWithTag("host_switcher_forget").performClick()
        // The menu item only OPENS the dialog; nothing is forgotten until the dialog confirms.
        assertEquals(null, forgotten)
        onNodeWithTag("host_forget_confirm").performClick()
        assertEquals("h2", forgotten)
    }

    @Test fun anOfflineHost_carriesItsLastSeenSuffix() = runComposeUiTest {
        // h2 was last seen 5 minutes before `nowMs`; h1 is online and carries no suffix.
        setContent(switcher(nowMs = 1_000_000L + 5 * 60_000L))
        onNodeWithTag("host_switcher").performClick()
        onNodeWithText("Raspberry Pi · 5m ago").assertIsDisplayed()
        onNodeWithText("MacBook").assertIsDisplayed()
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
        onNodeWithText("Raspberry").assertIsDisplayed()
    }

    @Test fun scopePickerAnchor_isChipSizedOnAWideWindow() = runComposeUiTest {
        // The DropdownMenu anchors to this box: a full-pane anchor on a wide window would drop a
        // menu the width of the whole settings pane, which desktop's own picker deliberately avoided.
        setContent {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Expanded) {
                Box(Modifier.width(400.dp)) {
                    HostScopePicker(hosts, selectedHostId = "h1", onSelect = {})
                }
            }
        }
        val width = onNodeWithTag("host_scope_picker").fetchSemanticsNode().size.width
        assertTrue(width < 400, "wide-window anchor should be chip-sized, was $width px of 400")
    }

    @Test fun scopePickerAnchor_spansThePaneWhenCompact() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                Box(Modifier.width(400.dp)) {
                    HostScopePicker(hosts, selectedHostId = "h1", onSelect = {})
                }
            }
        }
        assertEquals(400, onNodeWithTag("host_scope_picker").fetchSemanticsNode().size.width)
    }
}
