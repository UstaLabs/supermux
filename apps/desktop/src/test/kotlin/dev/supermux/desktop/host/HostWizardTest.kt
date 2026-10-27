package dev.supermux.desktop.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.host.PairingPayload
import dev.supermux.ui.widgets.qrBitmap
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proofs for the first-run host wizard: the pure payload builder round-trips through
 * the phone's [PairingPayload.parse], and [HostWizardContent] renders the spec §6 copy, a
 * CHECKED-by-default keep-alive box, the relay-disclosure line, and the QR — in-process, no display.
 */
@OptIn(ExperimentalTestApi::class)
class HostWizardTest {

    private val validHostId = "abcdefghijklmnopqrstuvwxyz".take(26) // 26-char base32 (a-z ⊂ a-z2-7)

    @Test fun buildsAPayloadThePhoneCanParse_directOnly() {
        val payload = buildPairingPayload(
            hostId = validHostId,
            name = "This computer",
            claimSecret = "one-time-claim-abc",
            directUrl = "http://127.0.0.1:9898",
            relayUrl = null,
        )
        val jsonStr = encodePairingPayload(payload)
        val parsed = assertNotNull(PairingPayload.parse(jsonStr), "phone must parse the wizard's payload")
        assertEquals(1, parsed.v)
        assertEquals("pair", parsed.action)
        assertEquals(validHostId, parsed.hostId)
        assertEquals("one-time-claim-abc", parsed.claimSecret)
        assertEquals("http://127.0.0.1:9898", parsed.directUrl)
        assertNull(parsed.relayUrl)
    }

    @Test fun buildsAPayloadThePhoneCanParse_withRelay() {
        val payload = buildPairingPayload(
            hostId = validHostId,
            name = "This computer",
            claimSecret = "claim-2",
            directUrl = "http://127.0.0.1:9898",
            relayUrl = "https://abc.relay.supermux.dev",
        )
        val parsed = assertNotNull(PairingPayload.parse(encodePairingPayload(payload)))
        assertEquals("https://abc.relay.supermux.dev", parsed.relayUrl)
    }

    @Test fun rejectsAForeignRelayOrigin() {
        // A non-supermux relay must be rejected by parse (anti-spoof, spec §3.4) — proves the wizard
        // can't accidentally emit a payload pointing the phone at an attacker relay.
        val payload = buildPairingPayload(
            hostId = validHostId, name = "x", claimSecret = "c",
            directUrl = null, relayUrl = "https://evil.example.com",
        )
        assertNull(PairingPayload.parse(encodePairingPayload(payload)))
    }

    @Test fun renders_readyState_withCopyCheckboxRelayAndQr() = runComposeUiTest {
        val qr = qrBitmap("{\"v\":1}", sizePx = 200)
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Ready(payloadJson = "{\"v\":1}", qr = qr, relayEnabled = false),
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
            )
        }
        onNodeWithTag("host_wizard_headline").assertIsDisplayed()
        onNodeWithText(HOST_WIZARD_HEADLINE).assertIsDisplayed()
        onNodeWithTag("host_wizard_qr").assertIsDisplayed()
        onNodeWithTag("host_wizard_keepalive_checkbox").assertIsOn() // CHECKED by default (spec §6)
        onNodeWithText(HOST_WIZARD_KEEPALIVE_LABEL).assertIsDisplayed()
        onNodeWithText(HOST_WIZARD_KEEPALIVE_HELP).assertIsDisplayed()
        assertEquals("Keep running in the background", HOST_WIZARD_KEEPALIVE_LABEL)
        onNodeWithTag("host_wizard_relay_disclosure").assertIsDisplayed()
        onNodeWithTag("host_wizard_done").assertIsDisplayed()
    }

    private val macGitMissing = dev.supermux.net.GitRequirement(ok = false, install = "xcode-select", hint = "Install Apple's Command Line Tools")

    @Test fun offers_the_optional_developer_tools_under_the_qr_and_installs_only_on_a_click() = runComposeUiTest {
        val qr = qrBitmap("x", sizePx = 120)
        var installs = 0
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Ready(payloadJson = "x", qr = qr, relayEnabled = false),
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
                gitRequirement = macGitMissing,
                onInstallGit = { installs++; true },
            )
        }
        onNodeWithTag(GitToolsTags.CARD).assertExists()
        onNodeWithText(GitToolsCopy.TITLE_MAC).assertExists()
        onNodeWithText(GitToolsCopy.BODY_MAC).assertExists()
        onNodeWithTag(GitToolsTags.SKIP).assertExists()
        waitForIdle()
        assertEquals(0, installs, "nothing is installed until the user clicks Install")
        onNodeWithTag(GitToolsTags.INSTALL).performClick()
        waitForIdle()
        assertEquals(1, installs)
        onNodeWithText(GitToolsCopy.INSTALLING).assertExists()
        onNodeWithTag(GitToolsTags.INSTALL).assertIsNotEnabled()
    }

    @Test fun skip_hides_the_developer_tools_step() = runComposeUiTest {
        val qr = qrBitmap("x", sizePx = 120)
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Ready(payloadJson = "x", qr = qr, relayEnabled = false),
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
                gitRequirement = macGitMissing,
            )
        }
        onNodeWithTag(GitToolsTags.SKIP).performClick()
        onNodeWithTag(GitToolsTags.CARD).assertDoesNotExist()
        onNodeWithTag("host_wizard_done").assertIsDisplayed()
    }

    @Test fun no_developer_tools_step_when_git_is_there() = runComposeUiTest {
        val qr = qrBitmap("x", sizePx = 120)
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Ready(payloadJson = "x", qr = qr, relayEnabled = false),
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
                gitRequirement = dev.supermux.net.GitRequirement(ok = true),
            )
        }
        onNodeWithTag(GitToolsTags.CARD).assertDoesNotExist()
    }

    @Test fun the_developer_tools_step_follows_the_brokers_report() {
        val idle = assertNotNull(gitToolsUi(macGitMissing, skipped = false, phase = GitToolsPhase.IDLE))
        assertEquals(GitToolsCopy.INSTALL, idle.installLabel)
        assertNull(idle.status)
        assertNull(idle.hint, "the Mac copy says what it installs")
        assertTrue(idle.installEnabled)
        // After Install: Installing… until the broker reports git ok, then nothing at all.
        val started = assertNotNull(gitToolsUi(macGitMissing, false, GitToolsPhase.STARTED))
        assertEquals(GitToolsCopy.INSTALLING, started.status)
        assertTrue(!started.installEnabled)
        assertNull(gitToolsUi(macGitMissing.copy(ok = true), false, GitToolsPhase.STARTED))
        assertNull(gitToolsUi(macGitMissing, skipped = true, phase = GitToolsPhase.IDLE))
        assertNull(gitToolsUi(null, false, GitToolsPhase.IDLE))
        // The broker's failure report wins: its error, and Retry.
        val failed = assertNotNull(gitToolsUi(macGitMissing.copy(installError = "cancelled"), false, GitToolsPhase.STARTED))
        assertEquals(GitToolsCopy.installFailed("cancelled"), failed.status)
        assertEquals(GitToolsCopy.RETRY, failed.installLabel)
        assertTrue(failed.installEnabled)
        assertEquals(GitToolsCopy.FAILED, gitToolsUi(macGitMissing, false, GitToolsPhase.FAILED)?.status)
        // Elsewhere: the generic copy and the broker's hint; a manual-only host has no Install.
        val linux = assertNotNull(gitToolsUi(dev.supermux.net.GitRequirement(ok = false, install = "manual", hint = "sudo apt install git"), false, GitToolsPhase.IDLE))
        assertEquals(GitToolsCopy.TITLE, linux.title)
        assertEquals("sudo apt install git", linux.hint)
        assertNull(linux.installLabel)
    }

    @Test fun renders_relayOnDisclosure_whenRelayEnabled() = runComposeUiTest {
        val qr = qrBitmap("x", sizePx = 120)
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Ready(payloadJson = "x", qr = qr, relayEnabled = true),
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
            )
        }
        // The relay-on copy names the supermux relay so the disclosure is truthful about remote access.
        onNodeWithText("Remote access is on through relay.supermux.dev.").assertIsDisplayed()
    }

    @Test fun renders_preparingSpinner() = runComposeUiTest {
        setContent {
            HostWizardContent(
                state = HostWizardUiState.Preparing,
                keepAlive = true,
                onKeepAliveChange = {},
                onFinish = {},
                onConnectInstead = {},
            )
        }
        onNodeWithTag("host_wizard_progress").assertIsDisplayed()
    }

    @Test fun the_model_exposes_its_minted_local_token_after_prepare() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val model = HostWizardModel(
            scope = scope,
            hostName = "Mac",
            provideHostId = { validHostId },
            provideLocalUrl = { "http://127.0.0.1:9898" },
            mintClaim = { HostClaim(localToken = "fresh-token", claimSecret = "secret") },
            onPairThisComputer = { _, _, _ -> },
            onInstallKeepAlive = {},
            qrOf = { qrBitmap("x", sizePx = 64) },
        )
        assertNull(model.localToken)
        model.prepare()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { model.state.first { it !is HostWizardUiState.Preparing } }
        }
        assertEquals("fresh-token", model.localToken)
    }
}
