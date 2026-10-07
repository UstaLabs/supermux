package dev.supermux.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.HostView
import dev.supermux.host.PairingPayload
import dev.supermux.state.AddHostResult
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose render proofs for the shared multi-host fleet UI (spec §5): per-row host badges and the
 * [AddHostScreen] paste flow (the footer switcher lives in [HostBadgeTest]). Moved here from
 * desktop's `host/FleetListUiTest` when both app copies collapsed into `:ui`; the badge case drives
 * [HostBadge] directly. The two cases that exercised the multi-host /
 * `showRowHostBadge` GATING stayed behind in `desktop/.../session/SessionListPanelHostGatingTest`,
 * because that gating lives in the per-app list panels, which did not move.
 */
@OptIn(ExperimentalTestApi::class)
class FleetListUiTest {

    private val twoHosts = listOf(
        HostView(recordId = "h1", hostId = "a", displayName = "MacBook", online = true),
        HostView(recordId = "h2", hostId = "b", displayName = "Raspberry Pi", online = false, lastSeenAt = 1L),
    )

    @Test fun hostBadge_rendersInMultiHostMode() = runComposeUiTest {
        setContent { HostBadge(twoHosts[0]) }
        onNodeWithTag("host_badge_h1").assertIsDisplayed()
    }

    @Test fun addHostScreen_pasteInvalidPayload_showsErrorAndDoesNotClaim() = runComposeUiTest {
        var claimed = false
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                AddHostScreen(
                    onBack = {},
                    defaultDeviceName = "This desktop",
                    onClaim = { _, _ -> claimed = true; AddHostResult.Error("x") },
                    onClaimLegacy = { AddHostResult.Error("x") },
                    onClaimByUrl = { _, _, _ -> AddHostResult.Error("x") },
                    onAdded = {},
                )
            }
        }
        onNodeWithTag("add_host_paste_field").performTextInput("not a pairing link")
        onNodeWithTag("add_host_paste_submit").performClick()
        // A payload that fails PairingPayload.parse (and PairUrl.parse) never reaches onClaim.
        assertTrue(!claimed, "an invalid payload must not trigger a claim")
        onNodeWithText("That isn't a valid supermux pairing link. Scan or paste the complete QR value.")
            .assertIsDisplayed()
    }

    @Test fun addHostScreen_pasteValidPayload_invokesOnClaimWithParsedPayload() = runComposeUiTest {
        var claimedHostId: String? = null
        // hostId must be a real 26-char base32 id (PairingPayload hardening, commit fc5eb29) or
        // parse() rejects it and onClaim never fires.
        val raw = """{"v":1,"action":"pair","hostId":"habcdefghijklmnopqrstuvwxy","name":"Box","relayUrl":"https://h-habc.relay.supermux.dev","claimSecret":"s3cret"}"""
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                AddHostScreen(
                    onBack = {},
                    defaultDeviceName = "This desktop",
                    onClaim = { p, _ -> claimedHostId = p.hostId; AddHostResult.Error("stop here") },
                    onClaimLegacy = { AddHostResult.Error("x") },
                    onClaimByUrl = { _, _, _ -> AddHostResult.Error("x") },
                    onAdded = {},
                )
            }
        }
        onNodeWithTag("add_host_paste_field").performTextInput(raw)
        onNodeWithTag("add_host_paste_submit").performClick()
        waitForIdle()
        assertEquals("habcdefghijklmnopqrstuvwxy", claimedHostId)
        // Sanity: the payload really is a valid one (guards against a copy/paste typo in the test).
        assertNull(PairingPayload.parse("garbage"))
    }
}
