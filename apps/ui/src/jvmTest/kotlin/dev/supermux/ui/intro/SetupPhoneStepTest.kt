package dev.supermux.ui.intro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.DeviceDto
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The wizard's "Connect Your Phone" step — the port of Vue's `SetupStepPhone`.
 *
 * Covers the auto-mint on entry, the QR + link, the 1 s poll that flips to "connected" the moment
 * the broker reports a `last_seen_at`, the revoke-if-unused on leaving, "Refresh code"
 * (revoke-then-mint) and "Connect another phone".
 */
@OptIn(ExperimentalTestApi::class)
class SetupPhoneStepTest {

    private class FakeDevices {
        val mints = CopyOnWriteArrayList<String>()
        val revokes = CopyOnWriteArrayList<String>()
        /** What `devicesLoad` reports for the minted device; `null` = never seen. */
        val lastSeen = AtomicReference<String?>(null)
        val mintCounter = AtomicInteger(0)

        val actions = DevicesSettingsActions(
            devicesLoad = {
                mints.map { DeviceDto(name = it, last_seen_at = lastSeen.get()) }
            },
            deviceAdd = { name ->
                val minted = "$name-${mintCounter.incrementAndGet()}"
                mints.add(minted)
                AddDeviceResponse(url = "https://broker.test/pair/$minted", name = minted)
            },
            deviceRevoke = { name -> revokes.add(name); mints.remove(name); true },
        )
    }

    /**
     * The app scope stand-in: OUTLIVES the composable (nothing cancels it when the step leaves),
     * which is what the host must pass for the revoke to land. `remember`ed so a recomposition
     * does not hand the step a different scope each frame.
     */
    @Composable
    private fun hostScope(): CoroutineScope = remember { CoroutineScope(Dispatchers.Unconfined) }

    private fun ComposeUiTest.phoneStep(
        fake: FakeDevices,
        content: @Composable () -> Unit = { SetupPhoneStep(fake.actions, scope = hostScope()) },
    ) = setPlatformContent(
        platform = FakePlatform(),
        pointer = true,
        widthClass = WindowWidthClass.Expanded,
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
    }

    private fun ComposeUiTest.eventually(timeoutMillis: Long = 8_000, block: () -> Unit) =
        waitUntil(timeoutMillis = timeoutMillis) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    @Test fun mints_one_phone_device_and_shows_the_qr_and_link() = runComposeUiTest {
        val fake = FakeDevices()
        phoneStep(fake)
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        assertEquals(listOf("phone-1"), fake.mints.toList())
        onNodeWithTag("setup_phone_url").assertIsDisplayed()
        onNodeWithText("https://broker.test/pair/phone-1").assertIsDisplayed()
    }

    @Test fun the_poll_flips_to_connected_when_the_broker_reports_last_seen() = runComposeUiTest {
        val fake = FakeDevices()
        phoneStep(fake)
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        fake.lastSeen.set("2026-09-12T10:00:00Z")
        eventually { onNodeWithText("Your phone is connected").assertIsDisplayed() }
        onNodeWithTag("setup_phone_qr").assertDoesNotExist()
    }

    @Test fun leaving_with_an_unused_code_revokes_it() = runComposeUiTest {
        val fake = FakeDevices()
        var visible by mutableStateOf(true)
        phoneStep(fake) {
            if (visible) SetupPhoneStep(fake.actions, scope = hostScope())
        }
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        visible = false
        waitForIdle()
        eventually { assertTrue(fake.revokes.isNotEmpty()) }
        assertEquals(listOf("phone-1"), fake.revokes.toList())
    }

    @Test fun leaving_after_pairing_keeps_the_device() = runComposeUiTest {
        val fake = FakeDevices()
        var visible by mutableStateOf(true)
        phoneStep(fake) {
            if (visible) SetupPhoneStep(fake.actions, scope = hostScope())
        }
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        fake.lastSeen.set("2026-09-12T10:00:00Z")
        eventually { onNodeWithText("Your phone is connected").assertIsDisplayed() }
        visible = false
        waitForIdle()
        assertEquals(emptyList(), fake.revokes.toList())
    }

    @Test fun refresh_revokes_the_unused_code_then_mints_another() = runComposeUiTest {
        val fake = FakeDevices()
        phoneStep(fake)
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        onNodeWithText("Refresh code").performClick()
        eventually { onNodeWithText("https://broker.test/pair/phone-2").assertIsDisplayed() }
        assertEquals(listOf("phone-1"), fake.revokes.toList())
        assertEquals(2, fake.mintCounter.get())
    }

    @Test fun connect_another_phone_mints_again_after_pairing() = runComposeUiTest {
        val fake = FakeDevices()
        phoneStep(fake)
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        fake.lastSeen.set("2026-09-12T10:00:00Z")
        eventually { onNodeWithText("Your phone is connected").assertIsDisplayed() }
        fake.lastSeen.set(null)
        onNodeWithText("Connect another phone").performClick()
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        assertEquals(2, fake.mintCounter.get())
        assertEquals(emptyList(), fake.revokes.toList())
    }

    /**
     * The regression the `rememberUpdatedState` version got wrong: Refresh mints a SECOND code, and
     * leaving right after must revoke that one — not re-revoke the first, which Refresh already
     * cleaned up.
     */
    @Test fun refresh_then_leaving_revokes_the_new_code_exactly_once() = runComposeUiTest {
        val fake = FakeDevices()
        var visible by mutableStateOf(true)
        phoneStep(fake) {
            if (visible) SetupPhoneStep(fake.actions, scope = hostScope())
        }
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        onNodeWithText("Refresh code").performClick()
        eventually { onNodeWithText("https://broker.test/pair/phone-2").assertIsDisplayed() }
        assertEquals(listOf("phone-1"), fake.revokes.toList())

        visible = false
        waitForIdle()
        eventually { assertTrue(fake.revokes.size >= 2) }
        assertEquals(listOf("phone-1", "phone-2"), fake.revokes.toList())
    }

    @Test fun copy_link_puts_the_pairing_url_on_the_clipboard() = runComposeUiTest {
        val fake = FakeDevices()
        val platform = FakePlatform()
        setPlatformContent(platform = platform, pointer = true, widthClass = WindowWidthClass.Expanded) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SetupPhoneStep(fake.actions, scope = hostScope())
            }
        }
        eventually { onNodeWithTag("setup_phone_qr").assertIsDisplayed() }
        onNodeWithText("Copy pairing link").performClick()
        eventually { assertTrue(platform.copied.isNotEmpty()) }
        assertEquals("https://broker.test/pair/phone-1", platform.copied.last())
        eventually { onNodeWithText("Copied").assertIsDisplayed() }
    }

    @Test fun a_failed_mint_shows_an_error_with_a_retry() = runComposeUiTest {
        val actions = DevicesSettingsActions(
            devicesLoad = { emptyList() },
            deviceAdd = { null },
            deviceRevoke = { false },
        )
        setPlatformContent(platform = FakePlatform(), pointer = true, widthClass = WindowWidthClass.Expanded) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SetupPhoneStep(actions, scope = hostScope())
            }
        }
        eventually { onNodeWithTag("setup_phone_error").assertIsDisplayed() }
        onNodeWithText("Try again").assertIsDisplayed()
    }
}
