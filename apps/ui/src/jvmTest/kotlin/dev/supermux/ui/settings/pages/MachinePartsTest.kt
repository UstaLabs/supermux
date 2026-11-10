package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AccountUsage
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.ClaudeUsage
import dev.supermux.net.ClaudeWindow
import dev.supermux.net.CodexUsage
import dev.supermux.net.CodexWindow
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.ProxyDto
import dev.supermux.net.UpdateStatus
import dev.supermux.net.UsageResponse
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeOrphanPageDto
import dev.supermux.net.WorktreeOrphansDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingPhase
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.settings.ProxiesSettingsActions
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.usage.UsageActions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The machine page's own parts (#97 Step 2 S2-3, spec §2.4, §10.12, §13; prototype step2.js): Devices, Shared links,
 * Disk and Limits. Carries the behaviour today's Devices, Proxies and Worktrees screens were tested for.
 */
@OptIn(ExperimentalTestApi::class)
class MachinePartsTest {

    private val server = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
    private val offline = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2 h ago")
    private val system = SystemSettingsActions(updateStatus = { UpdateStatus(current = "0.17.5", mode = "binary") })

    private fun ComposeUiTest.page(
        devices: DevicesSettingsActions = DevicesSettingsActions(devicesLoad = { emptyList() }),
        links: ProxiesSettingsActions = ProxiesSettingsActions(proxiesLoad = { emptyList() }),
        worktrees: WorktreesSettingsActions = WorktreesSettingsActions(load = { WorktreeListDto("/r") }),
        usage: UsageActions? = null,
        machine: KitMachine = server,
        hosting: ThisComputerHosting? = null,
        platform: FakePlatform = FakePlatform(),
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        onOpenUsage: () -> Unit = {},
        timings: PairTimings = PairTimings(),
        chats: MachineChats = MachineChats(3, listOf("Nightly eval run")),
    ) = setPlatformContent(platform = platform, widthClass = widthClass, pointer = widthClass != WindowWidthClass.Compact) {
        CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(machine), machine.id) {}) {
            MachinePage(
                system, chats = chats, hosting = hosting, devices = devices, links = links, worktrees = worktrees,
                usage = usage, onOpenUsage = onOpenUsage, pairTimings = timings,
            )
        }
    }

    private fun ComposeUiTest.await(tag: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { onNodeWithTag(tag).assertExists(); true }.getOrDefault(false) }

    private fun ComposeUiTest.gone(tag: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { onNodeWithTag(tag).assertDoesNotExist(); true }.getOrDefault(false) }

    private fun ComposeUiTest.click(tag: String) {
        await(tag)
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    /** In a sheet: it scrolls within the sheet. */
    private fun ComposeUiTest.tap(tag: String) = click(tag)

    /** [text] is on the node tagged [tag] or on one inside it. */
    private fun ComposeUiTest.has(tag: String, text: String) {
        onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasText(text, substring = true, ignoreCase = true),
            useUnmergedTree = true,
        ).onFirst().assertExists()
    }

    private fun ComposeUiTest.hasSoon(tag: String, text: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { has(tag, text); true }.getOrDefault(false) }

    // ── Devices ─────────────────────────────────────────────────────────────────────────────────

    private val fold = DeviceDto("Galaxy Z Fold7", last_seen_at = "2024-06-01T12:00:00Z", current = true)
    private val mac = DeviceDto("Ahmet's MacBook", last_seen_at = "2024-06-01T11:58:00Z")
    private val fresh = DeviceDto("work-laptop")

    @Test fun this_device_is_marked_and_cannot_be_revoked() = runComposeUiTest {
        page(devices = DevicesSettingsActions(devicesLoad = { listOf(fold, mac, fresh) }))
        await("device_row_Galaxy Z Fold7")
        has("device_row_Galaxy Z Fold7", "This device")
        has("device_row_Galaxy Z Fold7", "Connected now")
        onNodeWithTag("device_revoke_Galaxy Z Fold7").assertIsNotEnabled()
        onNodeWithTag("device_revoke_Ahmet's MacBook").assertIsEnabled()
        has("device_row_work-laptop", "Not used yet")
        onNodeWithTag("machine_page").assertExists()
    }

    @Test fun last_seen_reads_as_words() {
        val now = 1_717_243_200_000L // 2024-06-01T12:00:00Z
        fun seen(ts: String?) = MachinePartsCopy.seen(DeviceDto("d", last_seen_at = ts), now)
        assertEquals("Last seen just now", seen("2024-06-01T11:59:40Z"))
        assertEquals("Last seen 2 min ago", seen("2024-06-01T11:58:00Z"))
        assertEquals("Last seen 3 h ago", seen("2024-06-01T09:00:00Z"))
        assertEquals("Last seen yesterday", seen("2024-05-31T10:00:00Z"))
        assertEquals("Last seen 12 days ago", seen("2024-05-20T12:00:00Z"))
        assertEquals("Not used yet", seen(null))
    }

    @Test fun a_failed_device_load_says_so_and_try_again_recovers() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(devices = DevicesSettingsActions(devicesLoad = { if (loads.incrementAndGet() == 1) null else listOf(mac) }))
        await("devices_settings_error")
        has("devices_settings_error", "Couldn’t load the devices.")
        onNodeWithTag("devices_settings_empty").assertDoesNotExist()
        click("devices_settings_retry")
        await("device_row_Ahmet's MacBook")
        gone("devices_settings_error")
    }

    @Test fun a_failed_device_load_recovers_on_its_own() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(devices = DevicesSettingsActions(devicesLoad = { if (loads.incrementAndGet() == 1) null else listOf(mac) }))
        await("devices_settings_error")
        await("device_row_Ahmet's MacBook", timeout = 8_000)
        assertTrue(loads.get() >= 2)
    }

    @Test fun no_devices_is_not_an_error_and_pairing_is_still_there() = runComposeUiTest {
        page()
        await("devices_settings_empty")
        onNodeWithTag("devices_settings_error").assertDoesNotExist()
        has("devices_add_button", "Pair a phone or computer")
    }

    @Test fun revoke_asks_first_then_the_list_reloads_without_it() = runComposeUiTest {
        var list = listOf(fold, mac)
        val revoked = mutableListOf<String>()
        page(devices = DevicesSettingsActions(
            devicesLoad = { list },
            deviceRevoke = { n -> revoked += n; list = list.filter { it.name != n }; true },
        ))
        click("device_revoke_Ahmet's MacBook")
        await("devices_revoke_dialog")
        has("devices_revoke_dialog", "Revoke Ahmet's MacBook?")
        has("devices_revoke_dialog", "It can’t reach studio-mini any more, and its open chats close on it. Pair it again to bring it back.")
        assertEquals(emptyList(), revoked)
        tap("devices_revoke_cancel")
        gone("devices_revoke_dialog")
        assertEquals(emptyList(), revoked)

        click("device_revoke_Ahmet's MacBook")
        tap("devices_revoke_confirm")
        gone("device_row_Ahmet's MacBook")
        assertEquals(listOf("Ahmet's MacBook"), revoked)
        onNodeWithTag("device_row_Galaxy Z Fold7").assertExists()
    }

    @Test fun a_refused_revoke_keeps_the_sheet_with_why_and_keeps_the_row() = runComposeUiTest {
        page(devices = DevicesSettingsActions(devicesLoad = { listOf(fold, mac) }, deviceRevoke = { false }))
        click("device_revoke_Ahmet's MacBook")
        tap("devices_revoke_confirm")
        await("devices_revoke_error")
        onNodeWithTag("devices_revoke_error").assertTextEquals("Couldn’t revoke Ahmet's MacBook. Try again.")
        onNodeWithTag("devices_revoke_dialog").assertExists()
        onNodeWithTag("device_row_Ahmet's MacBook").assertExists()
    }

    // ── Rename (#97) ──

    private fun ComposeUiTest.typeName(text: String) {
        onNodeWithTag("devices_rename_field").performTextClearance()
        onNodeWithTag("devices_rename_field").performTextInput(text)
        waitForIdle()
    }

    @Test fun rename_opens_with_the_current_name_and_save_renames_then_reloads() = runComposeUiTest {
        var list = listOf(fold, mac)
        val renamed = mutableListOf<Pair<String, String>>()
        page(devices = DevicesSettingsActions(
            devicesLoad = { list },
            deviceRename = { from, to ->
                renamed += from to to
                list = list.map { if (it.name == from) it.copy(name = to) else it }
                AccountResult.Ok(list.first { it.name == to })
            },
        ))
        click("device_rename_Ahmet's MacBook")
        await("devices_rename_dialog")
        has("devices_rename_dialog", "Rename device")
        // The label, then the name in the field.
        onNodeWithTag("devices_rename_field").assertTextEquals("Name", "Ahmet's MacBook")
        typeName("  Studio laptop ")
        tap("devices_rename_save")
        gone("devices_rename_dialog")
        assertEquals(listOf("Ahmet's MacBook" to "Studio laptop"), renamed)
        await("device_row_Studio laptop")
        onNodeWithTag("device_row_Ahmet's MacBook").assertDoesNotExist()
    }

    @Test fun this_device_can_be_renamed() = runComposeUiTest {
        val renamed = mutableListOf<Pair<String, String>>()
        page(devices = DevicesSettingsActions(
            devicesLoad = { listOf(fold, mac) },
            deviceRename = { from, to -> renamed += from to to; AccountResult.Ok(fold.copy(name = to)) },
        ))
        await("device_rename_Galaxy Z Fold7")
        onNodeWithTag("device_rename_Galaxy Z Fold7").assertIsEnabled()
        click("device_rename_Galaxy Z Fold7")
        typeName("My Fold")
        tap("devices_rename_save")
        gone("devices_rename_dialog")
        assertEquals(listOf("Galaxy Z Fold7" to "My Fold"), renamed)
    }

    @Test fun a_taken_name_is_refused_in_the_sheet_and_the_sheet_stays() = runComposeUiTest {
        page(devices = DevicesSettingsActions(
            devicesLoad = { listOf(fold, mac) },
            deviceRename = { _, _ -> AccountResult.Failed(409, "name_taken", "Another device already has that name.") },
        ))
        click("device_rename_Ahmet's MacBook")
        typeName("Galaxy Z Fold7")
        tap("devices_rename_save")
        await("devices_rename_error")
        onNodeWithTag("devices_rename_error").assertTextEquals("Another device already has that name.")
        onNodeWithTag("devices_rename_dialog").assertExists()
        // Editing the name clears the refusal.
        onNodeWithTag("devices_rename_field").performTextInput("2")
        gone("devices_rename_error")
    }

    @Test fun any_other_refusal_says_try_again() = runComposeUiTest {
        page(devices = DevicesSettingsActions(
            devicesLoad = { listOf(mac) },
            deviceRename = { _, _ -> AccountResult.Failed(null, null, "timeout") },
        ))
        click("device_rename_Ahmet's MacBook")
        typeName("Studio")
        tap("devices_rename_save")
        await("devices_rename_error")
        onNodeWithTag("devices_rename_error").assertTextEquals("Couldn’t rename Ahmet's MacBook. Try again.")
    }

    @Test fun a_blank_name_cannot_be_saved_and_the_same_name_asks_nothing() = runComposeUiTest {
        val calls = AtomicInteger(0)
        page(devices = DevicesSettingsActions(
            devicesLoad = { listOf(mac) },
            deviceRename = { _, to -> calls.incrementAndGet(); AccountResult.Ok(mac.copy(name = to)) },
        ))
        click("device_rename_Ahmet's MacBook")
        typeName("   ")
        onNodeWithTag("devices_rename_save").assertIsNotEnabled()
        typeName(" Ahmet's MacBook ")
        tap("devices_rename_save")
        gone("devices_rename_dialog")
        assertEquals(0, calls.get())
    }

    @Test fun pairing_shows_the_code_at_once_with_its_link_and_clock() = runComposeUiTest {
        val url = "https://h-k2pq.relay.supermux.dev/pair?t=tok123"
        val minted = mutableListOf<String>()
        val platform = FakePlatform()
        page(
            devices = DevicesSettingsActions(devicesLoad = { emptyList() }, deviceAdd = { minted += it; AddDeviceResponse(url, it) }),
            platform = platform,
        )
        click("devices_add_button")
        await("devices_pairing_qr")
        // No name step: the code is minted the moment the sheet opens.
        assertEquals(listOf(MachinePartsCopy.PAIR_DEVICE_NAME), minted)
        has("devices_add_dialog", "Scan this with the supermux app on it, or open the link there.")
        onNodeWithTag("devices_pairing_url").assertTextEquals(url)
        has("devices_pairing_expires", "Works once · expires in ")
        has("devices_pairing_expires", "10:00")
        tap("devices_add_copy")
        assertEquals(listOf(url), platform.copied)
        onNodeWithTag("devices_add_copy").assertTextEquals("Copied")
        tap("devices_add_dismiss")
        gone("devices_add_dialog")
    }

    @Test fun with_remote_access_off_the_link_is_the_local_address_and_says_same_network() = runComposeUiTest {
        val hosting = ThisComputerHosting(
            HostingUiState(
                hosting = true, statusDot = "", statusText = "", readOnly = false, localUrl = "http://192.168.1.24:9898",
                relayUrl = null, relay = false, background = true, sessions = 0, logTail = emptyList(), phase = HostingPhase.Running,
            ),
            NoHosting,
        )
        page(
            devices = DevicesSettingsActions(devicesLoad = { emptyList() }, deviceAdd = { AddDeviceResponse("http://127.0.0.1:9898/pair?t=abc", it) }),
            machine = KitMachine("m1", "Ahmet's MacBook", local = true, online = true, status = "Running"),
            hosting = hosting,
        )
        click("devices_add_button")
        await("devices_pairing_url")
        onNodeWithTag("devices_pairing_url").assertTextEquals("http://192.168.1.24:9898/pair?t=abc")
        has("devices_add_dialog", "It needs to be on the same network: reaching it from anywhere is off.")
    }

    @Test fun an_unused_code_runs_out_and_its_device_is_revoked() = runComposeUiTest {
        val revoked = mutableListOf<String>()
        page(
            devices = DevicesSettingsActions(
                devicesLoad = { listOf(DeviceDto("New device")) },
                deviceAdd = { AddDeviceResponse("https://x.relay.supermux.dev/pair?t=a", it) },
                deviceRevoke = { revoked += it; true },
            ),
            timings = PairTimings(ttlMs = 1_200, pollMs = 300),
        )
        click("devices_add_button")
        await("devices_pairing_qr")
        await("devices_pair_expired", timeout = 6_000)
        waitUntil(timeoutMillis = 5_000) { revoked == listOf("New device") }
    }

    @Test fun new_code_revokes_the_unused_one_and_makes_another() = runComposeUiTest {
        val minted = AtomicInteger(0)
        val revoked = mutableListOf<String>()
        page(
            devices = DevicesSettingsActions(
                devicesLoad = { emptyList() },
                deviceAdd = { val n = minted.incrementAndGet(); AddDeviceResponse("https://x.relay.supermux.dev/pair?t=$n", "New device-$n") },
                deviceRevoke = { revoked += it; true },
            ),
        )
        click("devices_add_button")
        await("devices_pairing_url")
        tap("devices_pair_new")
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("devices_pairing_url").assertTextEquals("https://x.relay.supermux.dev/pair?t=2"); true }.getOrDefault(false) }
        assertEquals(listOf("New device-1"), revoked)
    }

    @Test fun a_used_code_says_the_device_is_paired() = runComposeUiTest {
        var used = false
        page(
            devices = DevicesSettingsActions(
                devicesLoad = { listOf(DeviceDto("New device", last_seen_at = if (used) "2024-06-01T12:00:00Z" else null)) },
                deviceAdd = { AddDeviceResponse("https://x.relay.supermux.dev/pair?t=a", it) },
            ),
            timings = PairTimings(pollMs = 200),
        )
        click("devices_add_button")
        await("devices_pairing_qr")
        used = true
        await("devices_pair_used")
        has("devices_pair_used", "New device is paired. It shows up under Devices.")
    }

    @Test fun a_failed_mint_says_so_and_tries_again() = runComposeUiTest {
        val calls = AtomicInteger(0)
        page(devices = DevicesSettingsActions(
            devicesLoad = { emptyList() },
            deviceAdd = { if (calls.incrementAndGet() == 1) null else AddDeviceResponse("https://x.relay.supermux.dev/pair?t=a", it) },
        ))
        click("devices_add_button")
        await("devices_add_error")
        has("devices_add_error", "Couldn’t make a pairing code on studio-mini.")
        tap("devices_pair_retry")
        await("devices_pairing_qr")
    }

    // ── Shared links ────────────────────────────────────────────────────────────────────────────

    private val preview = ProxyDto("app-preview", "Settings redesign", 3000, url = "https://x.relay.supermux.dev/p/app-preview/")
    private val issues = ProxyDto("issues", "Issue tracker", 18791, isPublic = true, url = "https://issues-ahmet.supermux.dev")

    @Test fun links_say_where_they_come_from_and_who_can_open_them() = runComposeUiTest {
        val platform = FakePlatform()
        page(links = ProxiesSettingsActions(proxiesLoad = { listOf(preview, issues) }), platform = platform)
        await("proxy_row_app-preview")
        has("proxy_row_app-preview", "app-preview · port 3000")
        has("proxy_row_app-preview", "From “Settings redesign” · Private: your paired devices")
        has("proxy_row_issues", "From “Issue tracker” · Public: anyone with the link")
        click("proxy_url_copy_app-preview")
        assertEquals(listOf(preview.url!!), platform.copied)
        click("proxy_url_open_issues")
        assertEquals(listOf(issues.url!!), platform.openedUrls)
    }

    @Test fun make_public_asks_first() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var list = listOf(preview)
        page(links = ProxiesSettingsActions(
            proxiesLoad = { list },
            proxySetPublic = { d, p -> calls += d to p; list = list.map { it.copy(isPublic = p) }; true },
        ))
        click("proxy_more_app-preview")
        tap("proxy_make_public_app-preview")
        await("proxies_public_dialog")
        has("proxies_public_dialog", "Make app-preview public?")
        has("proxies_public_dialog", "Anyone with the link can open port 3000 on studio-mini, not only your paired devices.")
        tap("proxies_public_cancel")
        assertEquals(emptyList(), calls)
        click("proxy_more_app-preview")
        tap("proxy_make_public_app-preview")
        tap("proxies_public_confirm")
        gone("proxies_public_dialog")
        assertEquals(listOf("app-preview" to true), calls)
        hasSoon("proxy_row_app-preview", "Public: anyone with the link")
    }

    @Test fun a_refused_make_public_keeps_the_sheet_with_why() = runComposeUiTest {
        page(links = ProxiesSettingsActions(proxiesLoad = { listOf(preview) }, proxySetPublic = { _, _ -> false }))
        click("proxy_more_app-preview")
        tap("proxy_make_public_app-preview")
        tap("proxies_public_confirm")
        await("proxies_public_error")
        onNodeWithTag("proxies_public_error").assertTextEquals("Couldn’t make the link public. Try again.")
    }

    @Test fun make_private_does_not_ask() = runComposeUiTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        page(links = ProxiesSettingsActions(proxiesLoad = { listOf(issues) }, proxySetPublic = { d, p -> calls += d to p; true }))
        click("proxy_more_issues")
        tap("proxy_make_private_issues")
        waitUntil(timeoutMillis = 5_000) { calls == listOf("issues" to false) }
        onNodeWithTag("proxies_public_dialog").assertDoesNotExist()
        hasSoon("proxy_row_issues", "Private: your paired devices")
    }

    @Test fun remove_asks_then_the_list_reloads_and_a_refusal_says_why() = runComposeUiTest {
        var list = listOf(preview, issues)
        var accept = false
        page(links = ProxiesSettingsActions(
            proxiesLoad = { list },
            proxyRemove = { d -> if (accept) { list = list.filter { it.domain != d }; true } else false },
        ))
        click("proxy_more_app-preview")
        tap("proxy_remove_app-preview")
        await("proxies_remove_dialog")
        has("proxies_remove_dialog", "Remove app-preview?")
        has("proxies_remove_dialog", "The link stops working. Port 3000 keeps running in “Settings redesign”.")
        tap("proxies_remove_confirm")
        await("proxies_remove_error")
        onNodeWithTag("proxies_remove_error").assertTextEquals("Couldn’t remove the link. Try again.")
        accept = true
        tap("proxies_remove_confirm")
        gone("proxy_row_app-preview")
        onNodeWithTag("proxy_row_issues").assertExists()
    }

    @Test fun share_a_port_needs_a_chat_and_a_valid_port_and_follows_the_live_chats() = runComposeUiTest {
        val created = mutableListOf<Triple<String, Int, String?>>()
        var list = emptyList<ProxyDto>()
        val sessions = MutableStateFlow(listOf("Nightly eval run", "greenmate API"))
        page(links = ProxiesSettingsActions(
            proxiesLoad = { list },
            sessionNames = sessions,
            proxyCreate = { s, p, d ->
                created += Triple(s, p, d)
                list = listOf(ProxyDto("eval", s, p, url = "https://x/p/eval/"))
                CreateProxyResponse("https://x/p/eval/", "eval", p)
            },
        ))
        click("proxies_expose_button")
        await("proxies_create_dialog")
        has("proxies_create_dialog", "Working")
        onNodeWithTag("proxies_create_confirm").assertIsNotEnabled()
        tap("proxies_create_session_item_Nightly eval run")
        onNodeWithTag("proxies_create_port").performTextInput("70000")
        waitForIdle()
        has("proxies_create_dialog", "A number from 1 to 65535.")
        onNodeWithTag("proxies_create_confirm").assertIsNotEnabled()
        // The live session list: a chat that appears after the sheet opened can be picked.
        sessions.value = sessions.value + "Release notes"
        await("proxies_create_session_item_Release notes")
        tap("proxies_create_session_item_Release notes")
        onNodeWithTag("proxies_create_confirm").assertIsNotEnabled()
        assertEquals(emptyList(), created)
    }

    @Test fun sharing_posts_the_pick_and_a_refusal_keeps_the_sheet() = runComposeUiTest {
        val created = mutableListOf<Triple<String, Int, String?>>()
        var accept = false
        var list = emptyList<ProxyDto>()
        page(links = ProxiesSettingsActions(
            proxiesLoad = { list },
            sessionNames = flowOf(listOf("Nightly eval run")),
            proxyCreate = { s, p, d ->
                created += Triple(s, p, d)
                if (!accept) null else {
                    list = listOf(ProxyDto("eval", s, p, url = "https://x/p/eval/"))
                    CreateProxyResponse("https://x/p/eval/", "eval", p)
                }
            },
        ))
        click("proxies_expose_button")
        tap("proxies_create_session_item_Nightly eval run")
        onNodeWithTag("proxies_create_port").performTextInput("8080")
        onNodeWithTag("proxies_create_domain").performTextInput("Eval Dash")
        waitForIdle()
        tap("proxies_create_confirm")
        await("proxies_create_error")
        onNodeWithTag("proxies_create_error").assertTextEquals("Couldn’t share port 8080. Try again.")
        accept = true
        tap("proxies_create_confirm")
        gone("proxies_create_dialog")
        await("proxy_row_eval")
        assertEquals<List<Triple<String, Int, String?>>>(listOf(Triple("Nightly eval run", 8080, "eval-dash"), Triple("Nightly eval run", 8080, "eval-dash")), created)
    }

    @Test fun a_failed_links_load_says_so_and_no_links_is_only_the_share_row() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(links = ProxiesSettingsActions(proxiesLoad = { if (loads.incrementAndGet() == 1) null else emptyList() }))
        await("proxies_settings_error")
        has("proxies_settings_error", "Couldn’t load the shared links.")
        click("proxies_settings_retry")
        gone("proxies_settings_error")
        has("proxies_expose_button", "Share a port…")
    }

    // ── Disk ────────────────────────────────────────────────────────────────────────────────────

    private fun w(
        id: String,
        changes: Int = 0,
        bytes: Long = 1L shl 30,
        live: Boolean = false,
        repo: String = "supermux",
        root: String = "/src/$repo",
    ) = WorktreeSummaryDto(
        id = id, path = "/wt/$id", repoRoot = root, repoName = repo, branch = "mux/$id",
        owners = listOf(WorktreeOwnerDto("s-$id", "Chat $id", if (live) "live" else "archived")),
        mtime = 0, uncommitted = changes, unmerged = 0, hasChanges = changes > 0, bytes = bytes,
    )

    private val trees = listOf(w("a"), w("b", bytes = 2L shl 30), w("dirty", changes = 6), w("busy", live = true))

    @Test fun disk_sums_up_and_review_keeps_folders_with_changes() = runComposeUiTest {
        page(worktrees = WorktreesSettingsActions(load = { WorktreeListDto("/wt", trees) }))
        await("machine_worktrees")
        hasSoon("machine_worktrees", "5.0 GB in 4 folders · 2 have no changes")
        click("wt_review")
        await("wt_review_sheet")
        has("wt_review_sheet", "Folders with changes are kept and can’t be picked.")
        has("wt_row_dirty", "6 changes")
        onNodeWithTag("wt_kept_dirty").assertExists()
        onNodeWithTag("wt_check_dirty").assertDoesNotExist()
        has("wt_row_busy", "In use by Chat busy")
        onNodeWithTag("wt_check_busy").assertDoesNotExist()
        onNodeWithTag("wt_delete").assertIsNotEnabled()
        onNodeWithTag("wt_picked").assertTextEquals("Nothing picked")
    }

    @Test fun select_without_changes_then_delete_asks_and_deletes_in_chunks_with_progress() = runComposeUiTest {
        val many = (1..45).map { w("c$it", bytes = 1L shl 20) } + w("dirty", changes = 1)
        var list = many
        val batches = mutableListOf<List<String>>()
        page(worktrees = WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", list) },
            delete = { ids ->
                batches += ids
                kotlinx.coroutines.delay(150)
                list = list.filter { it.id !in ids }
                ids.map { WorktreeDeleteResultDto(it, ok = true) }
            },
        ))
        hasSoon("machine_worktrees", " folders · ")
        click("wt_review")
        tap("wt_select_clean")
        onNodeWithTag("wt_select_clean").assertTextEquals("Clear")
        onNodeWithTag("wt_picked").assertTextEquals("45 picked · 45 MB")
        onNodeWithTag("wt_delete").assertTextEquals("Delete 45")
        tap("wt_delete")
        await("wt_delete_dialog")
        has("wt_delete_dialog", "Delete 45 worktrees?")
        has("wt_delete_dialog", "Deletes the folders and their branches · 45 MB. None of them has changes. This can’t be undone.")
        assertEquals(emptyList(), batches)
        tap("wt_delete_confirm")
        await("wt_delete_progress")
        has("wt_delete_progress", "Deleting ")
        gone("wt_review_sheet", timeout = 8_000)
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        hasSoon("machine_worktrees", "in 1 folder · all have changes")
    }

    @Test fun a_failed_delete_keeps_the_row_with_its_error() = runComposeUiTest {
        page(worktrees = WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", trees) },
            delete = { ids -> ids.map { WorktreeDeleteResultDto(it, ok = false, error = "in_use", inUseBy = listOf("Chat a")) } },
        ))
        hasSoon("machine_worktrees", " folders · ")
        click("wt_review")
        tap("wt_check_a")
        tap("wt_delete")
        tap("wt_delete_confirm")
        await("wt_error_a")
        onNodeWithTag("wt_error_a").assertTextEquals("In use by Chat a")
        onNodeWithTag("wt_review_sheet").assertExists()
    }

    @Test fun disk_asks_again_while_the_machine_is_still_counting() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(worktrees = WorktreesSettingsActions(load = {
            val n = loads.incrementAndGet()
            WorktreeListDto("/wt", if (n == 1) emptyList() else trees, scanning = n == 1)
        }))
        await("machine_worktrees")
        has("machine_worktrees", "Counting…")
        waitUntil(timeoutMillis = 8_000) { loads.get() >= 2 }
        hasSoon("machine_worktrees", "in 4 folders")
    }

    // ── Folders whose repository is gone (#97 S2-4) ──

    private fun goneW(id: String, live: Boolean = false) =
        w(id, bytes = 1L shl 20, live = live).copy(error = "repo gone", hasChanges = true, unmerged = null)

    @Test fun a_gone_repository_folder_is_picked_on_its_own_after_a_confirm_and_never_by_select_clean() = runComposeUiTest {
        val gone = listOf(goneW("g0"), goneW("g1"), goneW("g2", live = true))
        val pages = mutableListOf<Pair<Int, Int>>()
        val batches = mutableListOf<List<String>>()
        page(worktrees = WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a"), w("dirty", changes = 2)), orphans = WorktreeOrphansDto(count = 3, bytes = 3L shl 20, sized = 3)) },
            orphans = { offset, limit -> synchronized(pages) { pages += offset to limit }; WorktreeOrphanPageDto(gone, total = 3) },
            delete = { ids -> batches += ids; ids.map { WorktreeDeleteResultDto(it, ok = true) } },
        ))
        hasSoon("machine_worktrees", "in 5 folders · 1 has no changes")
        click("wt_review")
        await("wt_row_g0")
        assertEquals(listOf(0 to 200), synchronized(pages) { pages.toList() })
        has("wt_orphans", "REPOSITORY MISSING · 3 FOLDERS")
        has("wt_row_g0", "repository gone")
        // A chat still on it: kept.
        onNodeWithTag("wt_kept_g2").assertExists()
        onNodeWithTag("wt_check_g2").assertDoesNotExist()
        // "Select the 1 without changes" takes only the clean folder.
        tap("wt_select_clean")
        onNodeWithTag("wt_picked").assertTextEquals("1 picked · 1.0 GB")
        // Picking a gone one asks first; Cancel leaves it out.
        tap("wt_check_g0")
        await("wt_gone_dialog")
        has("wt_gone_dialog", "The repository is gone; this deletes the folder.")
        tap("wt_gone_cancel")
        await("wt_review_sheet")
        onNodeWithTag("wt_picked").assertTextEquals("1 picked · 1.0 GB")
        tap("wt_check_g0")
        tap("wt_gone_confirm")
        await("wt_review_sheet")
        onNodeWithTag("wt_picked").assertTextEquals("2 picked · 1.0 GB")
        // Clear drops only the clean ones; the gone one stays picked.
        tap("wt_select_clean")
        onNodeWithTag("wt_picked").assertTextEquals("1 picked · 1 MB")
        tap("wt_select_clean")
        // Unpicking needs no confirm.
        tap("wt_check_g0")
        onNodeWithTag("wt_gone_dialog").assertDoesNotExist()
        onNodeWithTag("wt_picked").assertTextEquals("1 picked · 1.0 GB")
        tap("wt_check_g0")
        tap("wt_gone_confirm")
        await("wt_review_sheet")
        tap("wt_delete")
        await("wt_delete_dialog")
        has("wt_delete_dialog", "1 of them have no repository any more; the rest have no changes.")
        tap("wt_delete_confirm")
        gone("wt_review_sheet")
        assertEquals(listOf(listOf("a", "g0")), batches)
    }

    @Test fun the_delete_ask_says_the_repository_is_gone_when_only_gone_folders_are_picked() {
        assertEquals(
            "Deletes the folder and its branch · 1 MB. Its repository is gone. This can’t be undone.",
            MachinePartsCopy.deleteText(1, 1L shl 20, gone = 1),
        )
        assertEquals(
            "Deletes the folders and their branches · 2 MB. Their repositories are gone. This can’t be undone.",
            MachinePartsCopy.deleteText(2, 2L shl 20, gone = 2),
        )
    }

    @Test fun more_gone_folders_load_a_page_at_a_time() = runComposeUiTest {
        val pages = mutableListOf<Int>()
        page(worktrees = WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a")), orphans = WorktreeOrphansDto(count = 3)) },
            orphans = { offset, _ ->
                synchronized(pages) { pages += offset }
                if (offset == 0) WorktreeOrphanPageDto(listOf(goneW("g0"), goneW("g1")), total = 3) else WorktreeOrphanPageDto(listOf(goneW("g2")), total = 3)
            },
        ))
        hasSoon("machine_worktrees", " folders · ")
        click("wt_review")
        await("wt_row_g1")
        onNodeWithTag("wt_orphans_more").assertTextEquals("Show more (1 left)")
        tap("wt_orphans_more")
        await("wt_row_g2")
        onNodeWithTag("wt_orphans_more").assertDoesNotExist()
        assertEquals(listOf(0, 2), synchronized(pages) { pages.toList() })
    }

    @Test fun a_live_size_does_not_move_a_row_in_review() = runComposeUiTest {
        var sizes by androidx.compose.runtime.mutableStateOf(mapOf<String, Long>())
        page(worktrees = WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a", bytes = 3L shl 20), w("b", bytes = 2L shl 20), w("c", bytes = 1L shl 20))) },
            sizes = { sizes },
        ))
        hasSoon("machine_worktrees", " folders · ")
        click("wt_review")
        await("wt_row_c")
        fun top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"))
        sizes = mapOf("c" to (10L shl 20))
        waitForIdle()
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"), "a size frame must not move rows")
        has("wt_row_c", "10 MB")
    }

    @Test fun same_named_repositories_on_windows_show_their_parent_folders() {
        val groups = worktreeGroups(
            listOf(w("x", repo = "repo", root = "C:\\src\\a\\repo"), w("y", repo = "repo", root = "C:\\src\\b\\repo")),
        )
        assertEquals(listOf("repo (C:\\src\\a)", "repo (C:\\src\\b)"), groups.map { it.first })
    }

    @Test fun review_groups_by_repository_root_and_tells_same_names_apart() {
        val groups = worktreeGroups(
            listOf(w("a", repo = "app", root = "/home/x/one/app"), w("b", repo = "app", root = "/home/x/two/app"), w("c", repo = "web")),
        )
        assertEquals(listOf("app (/home/x/one)", "app (/home/x/two)", "web"), groups.map { it.first })
    }

    @Test fun ages_read_as_words() {
        val day = 86_400_000L
        assertEquals("made today", MachinePartsCopy.age(day / 2, 0))
        assertEquals("3 days old", MachinePartsCopy.age(3 * day, 0))
        assertEquals("3 weeks old", MachinePartsCopy.age(21 * day, 0))
        assertEquals("2 months old", MachinePartsCopy.age(65 * day, 0))
    }

    // ── Limits ──────────────────────────────────────────────────────────────────────────────────

    private val usageData = UsageResponse(
        claude = ClaudeUsage(fiveHour = ClaudeWindow(18.0), sevenDay = ClaudeWindow(62.0)),
        codex = CodexUsage(windows = listOf(CodexWindow("w", 41.0, windowSeconds = 604_800.0))),
        accounts = listOf(
            AccountUsage("work", "claude", "Work", claude = ClaudeUsage(fiveHour = ClaudeWindow(91.0), sevenDay = ClaudeWindow(47.0))),
        ),
    )

    @Test fun limits_are_per_login_and_warn_from_ninety_percent() {
        val logins = limitLogins(usageData, now = 0)
        assertEquals(listOf("Claude Code · your login", "Claude Code · Work", "Codex"), logins.map { it.title })
        assertEquals(listOf("5-hour" to 18, "Weekly" to 62), logins[0].windows.map { it.label to it.percent })
        assertEquals(listOf(true, false), logins[1].windows.map { it.warn })
        assertEquals(listOf("Weekly" to 41), logins[2].windows.map { it.label to it.percent })
        assertEquals(emptyList(), limitLogins(null, 0))
    }

    @Test fun limits_show_each_login_refresh_and_open_full_usage() = runComposeUiTest {
        val snapshot = MutableStateFlow<UsageResponse?>(null)
        val refreshes = AtomicInteger(0)
        var opened = 0
        page(
            usage = UsageActions(
                snapshot = snapshot,
                load = { snapshot.value = usageData; usageData },
                refresh = { refreshes.incrementAndGet(); kotlinx.coroutines.delay(200); usageData },
                redeem = { null },
            ),
            onOpenUsage = { opened++ },
        )
        await("machine_limit_claude")
        has("machine_limit_claude", "Claude Code · your login")
        has("machine_limit_claude:work_5-hour", "91%")
        has("machine_limit_codex", "Codex")
        click("machine_limits_refresh")
        waitUntil(timeoutMillis = 5_000) { refreshes.get() == 1 }
        await("machine_limits_refresh")
        click("machine_open_usage")
        assertEquals(1, opened)
    }

    // ── Offline ─────────────────────────────────────────────────────────────────────────────────

    @Test fun an_offline_machine_gets_one_quiet_line_per_section() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(
            devices = DevicesSettingsActions(devicesLoad = { loads.incrementAndGet(); emptyList() }),
            machine = offline,
            usage = UsageActions(MutableStateFlow(null), load = { null }, refresh = { null }, redeem = { null }),
        )
        await("machine_devices_quiet")
        listOf("devices", "links", "disk", "limits").forEach {
            onNodeWithTag("machine_${it}_quiet").assertTextEquals("studio-mini is offline · last seen 2 h ago")
        }
        onNodeWithTag("devices_add_button").assertDoesNotExist()
        assertEquals(0, loads.get())
    }

    @Test fun the_pair_link_moves_to_the_local_address() {
        assertEquals("http://192.168.1.24:9898/pair?t=abc", rebaseUrl("http://127.0.0.1:9898/pair?t=abc", "http://192.168.1.24:9898/"))
        assertEquals("http://10.0.0.2:1", rebaseUrl("https://x.dev", "http://10.0.0.2:1"))
    }

    private object NoHosting : HostingActions {
        override fun setHosting(on: Boolean) = Unit
        override fun setBackground(on: Boolean) = Unit
        override fun setRelay(on: Boolean) = Unit
        override fun restart() = Unit
        override fun retry() = Unit
        override fun showLog() = Unit
        override fun pairDevice() = Unit
        override fun manageIt() = Unit
        override suspend fun installGit() = false
    }
}
