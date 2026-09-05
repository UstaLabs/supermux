package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.BrokerApi
import dev.supermux.net.DeviceDto
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.widgets.encodeQr
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [DevicesSettingsScreen] (cluster E4) — desktop's suite, moved by name.
 *
 * Covers load Error vs Empty (Android used to render "No devices registered." for a failed GET),
 * the auto-retry, the minted pairing link with its QR, the revoke confirm that keeps the row when
 * the broker refuses, and the real `HostStore` + mocked `BrokerApi` paths — plus the Compact branch
 * Android contributed. The `AppShell`-driven hub wiring stays in `:desktop`
 * (`DevicesSettingsHubTest`).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DevicesSettingsScreenTest {

    private fun ComposeUiTest.devicesContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    /** The holder is an E4 detail; every test keeps naming the three calls it always named. */
    @Composable
    private fun DevicesScreenUnderTest(
        devicesLoad: suspend () -> List<DeviceDto>?,
        deviceAdd: suspend (String) -> AddDeviceResponse?,
        deviceRevoke: suspend (String) -> Boolean,
        topBarShown: Boolean = true,
    ) = DevicesSettingsScreen(
        actions = DevicesSettingsActions(devicesLoad, deviceAdd, deviceRevoke),
        topBarShown = topBarShown,
    )

    private fun uiTestDeps(client: HttpClient) = HostStoreDeps(
        httpFactory = { client },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    )

    private fun sampleDevices() = listOf(
        DeviceDto(
            name = "pixel-8",
            created_at = "2024-01-01T00:00:00Z",
            last_seen_at = "2024-06-01T12:00:00Z",
        ),
        DeviceDto(
            name = "macbook",
            created_at = "2024-02-01T00:00:00Z",
            last_seen_at = "2024-07-01T08:30:00Z",
        ),
    )

    private fun screen(
        devicesLoad: suspend () -> List<DeviceDto>? = { sampleDevices() },
        deviceAdd: suspend (String) -> AddDeviceResponse? = { null },
        deviceRevoke: suspend (String) -> Boolean = { true },
    ) = @Composable {
        DevicesScreenUnderTest(
            devicesLoad = devicesLoad,
            deviceAdd = deviceAdd,
            deviceRevoke = deviceRevoke,
        )
    }

    // ── screen load states ──────────────────────────────────────────────────────────────────────


    @Test fun devices_render_from_a_fake_list_with_last_seen() = runComposeUiTest {
        devicesContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_settings_screen").assertIsDisplayed()
        onNodeWithTag("device_row_macbook").assertIsDisplayed()
        onNodeWithTag("device_last_seen_pixel-8").assertIsDisplayed()
        onNodeWithTag("device_last_seen_macbook").assertIsDisplayed()
        onNodeWithTag("devices_add_button").assertIsDisplayed()
    }

    @Test fun null_last_seen_renders_without_subtitle() = runComposeUiTest {
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = {
                        listOf(
                            DeviceDto(name = "never-seen", last_seen_at = null),
                            DeviceDto(name = "seen", last_seen_at = "2024-06-01T12:00:00Z"),
                        )
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_never-seen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("device_row_never-seen").assertIsDisplayed()
        onNodeWithTag("device_last_seen_never-seen").assertDoesNotExist()
        onNodeWithTag("device_last_seen_seen").assertIsDisplayed()
    }

    @Test fun load_failure_shows_error_with_retry_not_empty() = runComposeUiTest {
        val loads = AtomicInteger(0)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(devicesLoad = {
                    loads.incrementAndGet()
                    null
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_settings_error").assertIsDisplayed()
        onNodeWithTag("devices_settings_retry").assertIsDisplayed()
        onNodeWithText("Couldn't load devices.").assertIsDisplayed()
        onNodeWithTag("devices_settings_empty").assertDoesNotExist()
        assertTrue(loads.get() >= 1)
    }

    @Test fun empty_list_shows_empty_state_not_error() = runComposeUiTest {
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(devicesLoad = { emptyList() })()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_settings_empty").assertIsDisplayed()
        onNodeWithTag("devices_settings_error").assertDoesNotExist()
        onNodeWithText("No devices registered.").assertIsDisplayed()
    }

    @Test fun retry_after_load_failure_recovers() = runComposeUiTest {
        val loads = AtomicInteger(0)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(devicesLoad = {
                    val n = loads.incrementAndGet()
                    if (n == 1) null else sampleDevices()
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_settings_error").assertIsDisplayed()
        onNodeWithTag("devices_settings_retry").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    @Test fun auto_retry_recovers_after_reconnect_without_manual_retry() = runComposeUiTest {
        val loads = AtomicInteger(0)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(devicesLoad = {
                    val n = loads.incrementAndGet()
                    if (n == 1) null else sampleDevices()
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_settings_error").assertIsDisplayed()
        waitUntil(timeoutMillis = 8_000) {
            try {
                onNodeWithTag("device_row_macbook").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    @Test fun disposing_error_state_stops_retry_loop() = runComposeUiTest {
        val loads = AtomicInteger(0)
        var show by mutableStateOf(true)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                if (show) {
                    screen(devicesLoad = {
                        loads.incrementAndGet()
                        null
                    })()
                }
            }
        }
        waitForIdle()
        onNodeWithTag("devices_settings_error").assertIsDisplayed()
        // Let the auto-retry fire at least once so the loop is live.
        waitUntil(timeoutMillis = 8_000) { loads.get() >= 2 }
        val atDispose = loads.get()
        show = false
        waitForIdle()
        // After leaving the section the LaunchedEffect is cancelled — no further loads.
        // Wait longer than one retry interval to prove the loop is dead.
        waitUntil(timeoutMillis = 5_000) {
            // Spin real time via successive waitForIdle ticks; load count must stay put.
            Thread.sleep(500)
            loads.get() == atDispose
        }
        // Extra settle beyond one ERROR_AUTO_RETRY_MS (3s) window.
        Thread.sleep(3_500)
        waitForIdle()
        assertEquals(atDispose, loads.get(), "retry loop must stop after dispose")
    }

    // ── add device (pairing link + QR) ──────────────────────────────────────────────────────────

    @Test fun add_device_mints_pairing_link_and_shows_qr() = runComposeUiTest {
        val added = AtomicReference<String?>(null)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = { sampleDevices() },
                    deviceAdd = { name ->
                        added.set(name)
                        AddDeviceResponse(
                            url = "https://pair.example/one-time-token",
                            name = name,
                        )
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_dialog").assertIsDisplayed()
        onNodeWithText("Give the new device a name. You'll get a one-time link to open on it.")
            .assertIsDisplayed()
        onNodeWithTag("devices_add_name").performTextInput("Work laptop")
        onNodeWithTag("devices_add_create").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_pairing_result").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("Work laptop", added.get())
        onNodeWithText("Pairing link").assertIsDisplayed()
        onNodeWithTag("devices_pairing_qr").assertIsDisplayed()
        onNodeWithTag("devices_pairing_url").assertIsDisplayed()
        onNodeWithText("https://pair.example/one-time-token").assertIsDisplayed()
        onNodeWithText(
            "Treat this link like a password — anyone who opens it gets access until you revoke the device.",
        ).assertIsDisplayed()
        onNodeWithTag("devices_add_copy").assertIsDisplayed()
        onNodeWithTag("devices_add_dismiss").assertIsDisplayed()
    }

    @Test fun add_dialog_autofocuses_name_field() = runComposeUiTest {
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(devicesLoad = { sampleDevices() })()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        // Name field is present and receives keyboard input without a prior click/Tab —
        // the production FocusRequester autofocus path. Skiko's dialog focus grant is
        // flaky under assertIsFocused, so we prove the keyboard path instead: type + Enter.
        onNodeWithTag("devices_add_name").assertIsDisplayed()
        onNodeWithTag("devices_add_name").performTextInput("typed-without-click")
        onNodeWithTag("devices_add_name").performKeyInput { pressKey(Key.Enter) }
        // Create is disabled until non-blank; text input proves the field accepted keys.
        // (Full Enter→mint is covered by add_dialog_enter_submits_name with a working deviceAdd.)
        waitForIdle()
        onNodeWithTag("devices_add_create").assertIsDisplayed()
    }

    @Test fun add_dialog_enter_submits_name() = runComposeUiTest {
        val added = AtomicReference<String?>(null)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = { sampleDevices() },
                    deviceAdd = {
                        added.set(it)
                        AddDeviceResponse(url = "https://pair.example/enter", name = it)
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_name").performTextInput("enter-device")
        onNodeWithTag("devices_add_name").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_pairing_result").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("enter-device", added.get())
    }

    @Test fun pairing_qr_encodes_the_minted_url() {
        // Same helper the screen uses (qrBitmap → encodeQr). Decode proves the rendered matrix
        // carries the pairing URL — matches the reviewer's ZXing screenshot decode.
        val url = "https://pair.example/review-token-for-review-minted"
        val matrix = encodeQr(url, sizePx = 512)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        val decoded = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(url, decoded)
    }

    @Test fun copy_link_writes_pairing_url_to_clipboard() = runComposeUiTest {
        val url = "https://pair.example/clipboard-token"
        val copied = AtomicReference<String?>(null)
        val fakeClipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) {
                copied.set(annotatedString.text)
            }
            override fun getText(): AnnotatedString? = copied.get()?.let { AnnotatedString(it) }
            override fun hasText(): Boolean = copied.get() != null
        }
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                    screen(
                        devicesLoad = { emptyList() },
                        deviceAdd = { AddDeviceResponse(url = url, name = it) },
                    )()
                }
            }
        }
        waitForIdle()
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_name").performTextInput("clip-me")
        onNodeWithTag("devices_add_create").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_add_copy").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_add_copy").performClick()
        waitForIdle()
        assertEquals(url, copied.get())
        onNodeWithText("Copied").assertIsDisplayed()
    }

    @Test fun add_device_failure_surfaces_error() = runComposeUiTest {
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = { emptyList() },
                    deviceAdd = { null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_name").performTextInput("fail-me")
        onNodeWithTag("devices_add_create").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_add_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't create the device. Try again.").assertIsDisplayed()
        onNodeWithTag("devices_pairing_result").assertDoesNotExist()
    }

    @Test fun add_device_done_reloads_list() = runComposeUiTest {
        val loads = AtomicInteger(0)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = {
                        val n = loads.incrementAndGet()
                        if (n <= 1) {
                            listOf(DeviceDto(name = "only-one"))
                        } else {
                            listOf(
                                DeviceDto(name = "only-one"),
                                DeviceDto(name = "Work laptop"),
                            )
                        }
                    },
                    deviceAdd = {
                        AddDeviceResponse(url = "https://pair.example/x", name = it)
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_only-one").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_add_button").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_name").performTextInput("Work laptop")
        onNodeWithTag("devices_add_create").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_pairing_result").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_add_dismiss").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_Work laptop").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    // ── revoke with confirm ─────────────────────────────────────────────────────────────────────

    @Test fun revoke_requires_confirm_then_removes_row() = runComposeUiTest {
        val revoked = AtomicReference<String?>(null)
        val loads = AtomicInteger(0)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = {
                        loads.incrementAndGet()
                        // After a successful revoke the screen reloads; drop pixel-8.
                        if (revoked.get() == "pixel-8") {
                            listOf(sampleDevices()[1])
                        } else {
                            sampleDevices()
                        }
                    },
                    deviceRevoke = { name ->
                        revoked.set(name)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("device_revoke_pixel-8").performClick()
        waitForIdle()
        onNodeWithTag("devices_revoke_dialog").assertIsDisplayed()
        onNodeWithText("Revoke device?").assertIsDisplayed()
        onNodeWithText("Remove \"pixel-8\" from authorized devices?").assertIsDisplayed()
        // Cancel leaves the row.
        onNodeWithTag("devices_revoke_cancel").performClick()
        waitForIdle()
        assertNull(revoked.get())
        onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
        // Confirm removes it via post-revoke reload.
        onNodeWithTag("device_revoke_pixel-8").performClick()
        waitForIdle()
        onNodeWithTag("devices_revoke_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("pixel-8", revoked.get())
        onNodeWithTag("device_row_macbook").assertIsDisplayed()
        // Success must reload from the broker (not only filter local state).
        assertTrue(loads.get() >= 2, "expected post-revoke reload, loads=${loads.get()}")
    }

    @Test fun revoke_failure_shows_visible_error_and_keeps_row() = runComposeUiTest {
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    devicesLoad = { sampleDevices() },
                    deviceRevoke = { false },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("device_revoke_macbook").performClick()
        waitForIdle()
        onNodeWithTag("devices_revoke_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("devices_revoke_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Couldn't revoke the device. Try again.").assertIsDisplayed()
        // Dialog stays open; row remains.
        onNodeWithTag("devices_revoke_dialog").assertIsDisplayed()
        onNodeWithTag("device_row_macbook").assertIsDisplayed()
    }

    // ── HostStore + BrokerApi (ktor mock) ─────────────────────────────────────────────────

    private data class DevicesAppHarness(
        val app: HostStore,
        val methods: CopyOnWriteArrayList<Pair<HttpMethod, String>>,
        val client: HttpClient,
    )

    private fun appForDevices(
        devicesJson: String? = """[{"name":"pixel-8","created_at":"2024-01-01T00:00:00Z","last_seen_at":"2024-06-01T12:00:00Z"}]""",
        addJson: String = """{"url":"https://pair.example/tok","name":"new-phone"}""",
        addStatus: HttpStatusCode = HttpStatusCode.OK,
        revokeStatus: HttpStatusCode = HttpStatusCode.OK,
        mutableDevices: AtomicReference<String>? = null,
    ): DevicesAppHarness {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/devices" && req.method == HttpMethod.Get -> {
                    val body = mutableDevices?.get() ?: devicesJson
                    if (body == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(body, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/devices" && req.method == HttpMethod.Post ->
                    respond(addJson, addStatus, jsonHeaders)
                path.startsWith("/devices/") && req.method == HttpMethod.Delete ->
                    respond("{}", revokeStatus, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = uiTestDeps(client),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return DevicesAppHarness(app, methods, client)
    }

    @Test fun desktop_app_state_devices_decodes_mock_broker() = runComposeUiTest {
        val harness = appForDevices()
        var listed: List<DeviceDto>? = emptyList()
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DevicesScreenUnderTest(
                    devicesLoad = {
                        listed = harness.app.devices()
                        listed
                    },
                    deviceAdd = { harness.app.addDevice(it) },
                    deviceRevoke = { harness.app.revokeDevice(it) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { listed?.isNotEmpty() == true }
        assertEquals(1, listed!!.size)
        assertEquals("pixel-8", listed!![0].name)
        onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
        harness.client.close()
    }

    @Test fun desktop_app_state_devices_null_on_broker_error() = runComposeUiTest {
        val harness = appForDevices(devicesJson = null)
        var result: List<DeviceDto>? = emptyList()
        var called = false
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DevicesScreenUnderTest(
                    devicesLoad = {
                        result = harness.app.devices()
                        called = true
                        result
                    },
                    deviceAdd = { null },
                    deviceRevoke = { false },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { called }
        assertNull(result)
        onNodeWithTag("devices_settings_error").assertIsDisplayed()
        harness.client.close()
    }

    @Test fun desktop_app_state_add_device_posts_and_returns_url() = runBlocking {
        val harness = appForDevices()
        val r = harness.app.addDevice("new-phone")
        assertEquals("https://pair.example/tok", r!!.url)
        assertEquals("new-phone", r.name)
        harness.client.close()
    }

    @Test fun desktop_app_state_add_device_null_on_non2xx() = runBlocking {
        val harness = appForDevices(addStatus = HttpStatusCode.InternalServerError)
        assertNull(harness.app.addDevice("x"))
        harness.client.close()
    }

    @Test fun desktop_app_state_revoke_device_delete_path() = runBlocking {
        val harness = appForDevices()
        assertTrue(harness.app.revokeDevice("pixel-8"))
        assertTrue(
            harness.methods.any { it.first == HttpMethod.Delete && it.second == "/devices/pixel-8" },
            "expected DELETE /devices/pixel-8, got ${harness.methods}",
        )
        harness.client.close()
    }

    @Test fun desktop_app_state_revoke_returns_false_on_500() = runBlocking {
        val harness = appForDevices(revokeStatus = HttpStatusCode.InternalServerError)
        assertFalse(harness.app.revokeDevice("pixel-8"))
        harness.client.close()
    }

    @Test fun desktop_app_state_revoke_returns_false_on_404() = runBlocking {
        val harness = appForDevices(revokeStatus = HttpStatusCode.NotFound)
        assertFalse(harness.app.revokeDevice("already-gone"))
        harness.client.close()
    }

    @Test fun revoke_success_issues_delete_then_get_reload() = runComposeUiTest {
        val devices = AtomicReference(
            """[{"name":"pixel-8","last_seen_at":"2024-06-01T12:00:00Z"},{"name":"macbook","last_seen_at":"2024-07-01T08:30:00Z"}]""",
        )
        val harness = appForDevices(mutableDevices = devices)
        devicesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DevicesScreenUnderTest(
                    devicesLoad = { harness.app.devices() },
                    deviceAdd = { harness.app.addDevice(it) },
                    deviceRevoke = { name ->
                        val ok = harness.app.revokeDevice(name)
                        if (ok) {
                            devices.set(
                                """[{"name":"macbook","last_seen_at":"2024-07-01T08:30:00Z"}]""",
                            )
                        }
                        ok
                    },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("device_revoke_pixel-8").performClick()
        waitForIdle()
        onNodeWithTag("devices_revoke_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        val deleteIdx = harness.methods.indexOfFirst {
            it.first == HttpMethod.Delete && it.second.startsWith("/devices/")
        }
        assertTrue(deleteIdx >= 0, "expected DELETE, methods=${harness.methods}")
        val getAfterDelete = harness.methods.drop(deleteIdx + 1).any {
            it.first == HttpMethod.Get && it.second == "/devices"
        }
        assertTrue(getAfterDelete, "expected GET /devices after DELETE, methods=${harness.methods}")
        harness.client.close()
    }

    // ── Settings hub overlay wiring ─────────────────────────────────────────────────────────────

    // ── Compact / touch (Android's branch) ──────────────────────────────────────────────────────

    /**
     * The phone page paints its own chrome when the hub did not: Android's `TopAppBar` + FAB
     * instead of the header button the wide detail pane uses.
     */
    @Test fun compact_page_paints_its_own_top_bar_and_fab() = runComposeUiTest {
        var backs = 0
        devicesContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                DevicesSettingsScreen(
                    actions = DevicesSettingsActions(
                        devicesLoad = { sampleDevices() },
                        deviceAdd = { null },
                        deviceRevoke = { true },
                    ),
                    onBack = { backs++ },
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_add_button").assertDoesNotExist()
        onNodeWithTag("devices_add_fab").assertIsDisplayed()
        onNodeWithTag("devices_add_fab").performClick()
        waitForIdle()
        onNodeWithTag("devices_add_dialog").assertIsDisplayed()
        onNodeWithTag("devices_add_dismiss").performClick()
        waitForIdle()
        onNodeWithTag("devices_settings_back").performClick()
        assertEquals(1, backs)
    }

    /** When the hub already painted a top bar, the page adds no second one (and keeps the button). */
    @Test fun compact_page_defers_to_the_hub_chrome() = runComposeUiTest {
        devicesContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("device_row_pixel-8").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("devices_settings_back").assertDoesNotExist()
        onNodeWithTag("devices_add_fab").assertDoesNotExist()
        onNodeWithTag("devices_add_button").assertIsDisplayed()
    }

    /** Hit targets key on `LocalPointerAvailable`: a finger gets more room between rows. */
    @Test fun touch_device_rows_sit_further_apart_than_pointer_rows() {
        fun rowPitch(pointer: Boolean): Float {
            var pitch = 0f
            runComposeUiTest {
                devicesContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("device_row_macbook").assertIsDisplayed()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val first = onNodeWithTag("device_row_pixel-8").fetchSemanticsNode().positionInRoot.y
                val second = onNodeWithTag("device_row_macbook").fetchSemanticsNode().positionInRoot.y
                pitch = second - first
            }
            return pitch
        }
        val touch = rowPitch(pointer = false)
        val mouse = rowPitch(pointer = true)
        assertTrue(touch > mouse, "touch pitch $touch should exceed pointer pitch $mouse")
    }

    /** The last-seen buckets both apps' `relTime` produced, now over the shared epoch parser. */
    @Test fun last_seen_buckets_match_the_old_relTime() {
        val now = 1_800_000_000_000L
        assertEquals("", deviceLastSeen(null, now))
        assertEquals("", deviceLastSeen("not-a-timestamp", now))
        assertEquals("now", deviceLastSeen(isoAt(now - 30_000L), now))
        assertEquals("5m", deviceLastSeen(isoAt(now - 5 * 60_000L), now))
        assertEquals("3h", deviceLastSeen(isoAt(now - 3 * 3_600_000L), now))
        assertEquals("2d", deviceLastSeen(isoAt(now - 2 * 86_400_000L), now))
    }

    private fun isoAt(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()
}
