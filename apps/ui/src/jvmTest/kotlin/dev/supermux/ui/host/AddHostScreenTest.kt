package dev.supermux.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.AddHostResult
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared add-host screen: the Scan mode is a capability, not a platform check, and the
 * plain-HTTP opt-in is what decides the third `onClaimByUrl` argument.
 */
@OptIn(ExperimentalTestApi::class)
class AddHostScreenTest {

    private fun screen(
        platform: FakePlatform,
        needsInsecureOptIn: (String) -> Boolean = { false },
        onClaimByUrl: suspend (String, String, Boolean) -> AddHostResult = { _, _, _ -> AddHostResult.Error("stop") },
        onClaim: suspend (dev.supermux.host.PairingPayload, String) -> AddHostResult = { _, _ -> AddHostResult.Error("stop") },
    ): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalPlatform provides platform) {
            AddHostScreen(
                onBack = {},
                defaultDeviceName = "This device",
                onClaim = onClaim,
                onClaimLegacy = { AddHostResult.Error("stop") },
                onClaimByUrl = onClaimByUrl,
                onAdded = {},
                needsInsecureOptIn = needsInsecureOptIn,
            )
        }
    }

    @Test fun scanMode_isAbsentWithoutACamera() = runComposeUiTest {
        setContent(screen(FakePlatform(caps = NO_CAPS.copy(camera = false))))
        onNodeWithTag("add_host_mode_scan").assertDoesNotExist()
        onNodeWithTag("add_host_mode_paste").assertIsDisplayed()
        onNodeWithTag("add_host_mode_url").assertIsDisplayed()
    }

    @Test fun scanMode_isOfferedWithACamera_andScansThroughThePlatform() = runComposeUiTest {
        val platform = FakePlatform(caps = NO_CAPS.copy(camera = true), qrResult = "not a link")
        setContent(screen(platform))
        onNodeWithTag("add_host_mode_scan").assertIsDisplayed()
        onNodeWithTag("add_host_mode_scan").performClick()
        onNodeWithTag("add_host_scan").performClick()
        waitForIdle()
        assertEquals(1, platform.scans)
    }

    @Test fun insecureOptIn_gatesTheButtonAndFlipsTheThirdClaimArgument() = runComposeUiTest {
        var allowInsecure: Boolean? = null
        setContent(
            screen(
                FakePlatform(),
                needsInsecureOptIn = { it.startsWith("http://") },
                onClaimByUrl = { _, _, allow -> allowInsecure = allow; AddHostResult.Error("stop") },
            ),
        )
        onNodeWithTag("add_host_mode_url").performClick()
        onNodeWithTag("add_host_url_field").performTextInput("http://box.lan:9898")
        // Unencrypted, non-loopback → the opt-in appears and Connect stays disabled until it is ticked.
        onNodeWithTag("add_host_insecure_optin").assertIsDisplayed()
        onNodeWithTag("add_host_url_submit").assertIsNotEnabled()
        onNode(isToggleable() and hasAnyAncestor(hasTestTag("add_host_insecure_optin"))).performClick()
        onNodeWithTag("add_host_url_submit").performClick()
        waitForIdle()
        assertEquals(true, allowInsecure)
    }

    @Test fun aSecureUrl_needsNoOptInAndClaimsWithFalse() = runComposeUiTest {
        var allowInsecure: Boolean? = null
        setContent(
            screen(
                FakePlatform(),
                needsInsecureOptIn = { it.startsWith("http://") },
                onClaimByUrl = { _, _, allow -> allowInsecure = allow; AddHostResult.Error("stop") },
            ),
        )
        onNodeWithTag("add_host_mode_url").performClick()
        onNodeWithTag("add_host_url_field").performTextInput("https://box.tailnet.ts.net")
        onNodeWithTag("add_host_insecure_optin").assertDoesNotExist()
        onNodeWithTag("add_host_url_submit").performClick()
        waitForIdle()
        assertEquals(false, allowInsecure)
        assertTrue(true)
    }
}
