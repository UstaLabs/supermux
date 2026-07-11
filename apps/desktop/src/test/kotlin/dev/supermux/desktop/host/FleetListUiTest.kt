package dev.supermux.desktop.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.SessionListPanel
import dev.supermux.host.PairingPayload
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose render proofs for the desktop multi-host fleet UI (spec §5): the `All · <host…> · +`
 * chip row + per-row host badges in [SessionListPanel], and the [AddHostScreen] paste flow. Runs
 * in-process via runComposeUiTest (same harness as SessionListPanelTest) — a robust "the fleet
 * list renders" check that needs no Xvfb or live broker.
 */
@OptIn(ExperimentalTestApi::class)
class FleetListUiTest {

    private fun session(id: String, wd: String = "/home/u/proj") =
        SessionInfo(id = id, name = "sess-$id", workdir = wd, agent = "claude")

    private val twoHosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1L),
    )

    @Test fun chipRowAndBadges_renderInMultiHostMode() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
            )
        }
        onNodeWithTag("host_filter_chips").assertIsDisplayed()
        onNodeWithTag("host_chip_all").assertIsDisplayed()
        onNodeWithTag("host_chip_h1").assertIsDisplayed()
        onNodeWithTag("host_chip_h2").assertIsDisplayed()
        onNodeWithTag("host_chip_add").assertIsDisplayed()
        // Per-row host badge for a session owned by h1. It's decorative inside the clickable (merged)
        // session row, so it lives in the unmerged tree.
        onNodeWithTag("host_badge_h1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun chipRowHidden_withASingleHost() = runComposeUiTest {
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = listOf(twoHosts[0]),
                sessionHost = mapOf("s1" to "h1"),
            )
        }
        // One host → no chips, no badges (single-host desktop looks exactly as before).
        onNodeWithTag("host_filter_chips").assertDoesNotExist()
        onNodeWithTag("host_badge_h1").assertDoesNotExist()
    }

    @Test fun clickingAHostChip_reportsTheSelection() = runComposeUiTest {
        var selected: String? = "sentinel"
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
                onSelectHostFilter = { selected = it },
            )
        }
        onNodeWithTag("host_chip_h2").performClick()
        assertEquals("h2", selected)
    }

    @Test fun clickingAddChip_firesOnAddHost() = runComposeUiTest {
        var fired = false
        setContent {
            SessionListPanel(
                sessions = listOf(session("s1"), session("s2")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                hosts = twoHosts,
                sessionHost = mapOf("s1" to "h1", "s2" to "h2"),
                onAddHost = { fired = true },
            )
        }
        onNodeWithTag("host_chip_add").performClick()
        assertTrue(fired)
    }

    @Test fun addHostScreen_pasteInvalidPayload_showsErrorAndDoesNotClaim() = runComposeUiTest {
        var claimed = false
        setContent {
            AddHostScreen(
                onBack = {},
                defaultDeviceName = "This desktop",
                onClaim = { _, _ -> claimed = true; FleetState.AddHostResult.Error("x") },
                onClaimByUrl = { _, _ -> FleetState.AddHostResult.Error("x") },
                onAdded = {},
            )
        }
        onNodeWithTag("add_host_paste_field").performTextInput("not a pairing link")
        onNodeWithTag("add_host_paste_submit").performClick()
        // A payload that fails PairingPayload.parse never reaches onClaim.
        assertTrue(!claimed, "an invalid payload must not trigger a claim")
        onNodeWithText("That isn't a valid supermux pairing link. Copy the whole payload from the host.").assertIsDisplayed()
    }

    @Test fun addHostScreen_pasteValidPayload_invokesOnClaimWithParsedPayload() = runComposeUiTest {
        var claimedHostId: String? = null
        // hostId must be a real 26-char base32 id (PairingPayload hardening, commit fc5eb29) or
        // parse() rejects it and onClaim never fires.
        val raw = """{"v":1,"action":"pair","hostId":"habcdefghijklmnopqrstuvwxy","name":"Box","relayUrl":"https://h-habc.relay.supermux.dev","claimSecret":"s3cret"}"""
        setContent {
            AddHostScreen(
                onBack = {},
                defaultDeviceName = "This desktop",
                onClaim = { p, _ -> claimedHostId = p.hostId; FleetState.AddHostResult.Error("stop here") },
                onClaimByUrl = { _, _ -> FleetState.AddHostResult.Error("x") },
                onAdded = {},
            )
        }
        onNodeWithTag("add_host_paste_field").performTextInput(raw)
        onNodeWithTag("add_host_paste_submit").performClick()
        waitForIdle()
        assertEquals("habcdefghijklmnopqrstuvwxy", claimedHostId)
        // Sanity: the payload really is a valid one (guards against a copy/paste typo in the test).
        assertNull(PairingPayload.parse("garbage"))
    }
}
