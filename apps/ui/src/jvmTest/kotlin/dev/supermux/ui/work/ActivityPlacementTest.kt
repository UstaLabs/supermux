package dev.supermux.ui.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.ChatActions
import dev.supermux.ui.chat.ChatPanel
import dev.supermux.ui.chat.ChatState
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.DownloadedInstaller
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.session.SessionListScreen
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.testFleet
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.ui.update.AppUpdateScreen
import dev.supermux.update.ClientUpdateStatus
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Where the activity bar sits and what it is wired to (#95): directly under every phone top bar, in the wide
 * sidebar's footer above the update card, an icon on the collapsed sidebar's rail, the list it opens (a sheet on a
 * phone, a popover anchored to the bar on a wide host with a pointer), and the shell's notice for a download that
 * finished with nobody waiting. In the app theme: these assert layout.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityPlacementTest {
    // The centers the tests build run here: unconfined, so a start has run up to its first suspension by the time it
    // returns, and a running download is listed at once.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val files = mutableListOf<File>()

    @AfterTest fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    /** A download that has 42 of its 100 bytes and goes no further until the test ends. Nobody waits for it. */
    private fun DownloadCenter.running(key: String = "att:h/1", name: String = "big-video.mp4") {
        val source = object : DownloadSource {
            override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile? {
                progress(42, 100)
                awaitCancellation()
            }
        }
        start(key, name, "video/mp4", DownloadPurpose.File, source).release()
    }

    /** A finished download of a real file (the hand-over checks it is there), with nobody waiting. */
    private fun DownloadCenter.finished(
        key: String,
        name: String,
        purpose: DownloadPurpose = DownloadPurpose.File,
        handle: Any? = null,
    ) {
        val file = File.createTempFile("mux95", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        files += file
        val done = DownloadedFile(name, "application/pdf", 3, file.absolutePath, handle)
        val source = object : DownloadSource {
            override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile = done
        }
        start(key, name, done.mime, purpose, source).release()
        assertIs<DownloadState.Done>(items.value.first { it.key == key }.state)
    }

    private fun ComposeUiTest.phone(
        center: DownloadCenter,
        platform: FakePlatform = FakePlatform(),
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
        SupermuxTheme(appearance = AppearanceMode.LIGHT) {
            CompositionLocalProvider(LocalDownloads provides center, content = content)
        }
    }

    /** A desktop: a pointer and an Expanded window. */
    private fun ComposeUiTest.wide(
        center: DownloadCenter,
        platform: FakePlatform = FakePlatform(),
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform) {
        SupermuxTheme(appearance = AppearanceMode.LIGHT) {
            CompositionLocalProvider(LocalDownloads provides center, content = content)
        }
    }

    /** A sheet or a popover is up only once its animation has run. */
    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    /**
     * Gives work that hops threads (a hand-over checks its file off the main thread) up to [ms] to be [done], and
     * returns either way: the assertion after it says what was wanted.
     */
    private fun ComposeUiTest.settle(ms: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) {
            waitForIdle()
            Thread.sleep(10)
        }
    }

    // ── under every phone top bar ──────────────────────────────────────────────────────────────

    @Test fun onAPhoneTheBarSitsDirectlyUnderHomesTopBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { SessionListScreen(home = "/home/u", activeId = null, onOpen = {}, standalone = true) }
        val topBar = onNodeWithTag("session_list_logo_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bar = onNodeWithTag("activity_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bar.top >= topBar.bottom, "the bar starts at ${bar.top}, above the top bar's bottom ${topBar.bottom}")
        // Directly under it: before the list's own first row, the new-session card.
        val card = onNodeWithTag("new_session_row").getUnclippedBoundsInRoot()
        assertTrue(card.top >= bar.bottom, "the new-session card (${card.top}) starts above the bar's bottom ${bar.bottom}")
    }

    @Test fun onAPhoneTheBarSitsUnderAChatsHeader() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) {
            ChatPanel(
                session = SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"),
                state = ChatState(),
                actions = ChatActions(),
                draft = "",
                onDraftChange = {},
            )
        }
        val header = onNodeWithTag("chat_find_open").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bar = onNodeWithTag("activity_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bar.top >= header.bottom, "the bar starts at ${bar.top}, inside the chat header (${header.bottom})")
    }

    @Test fun onAPhoneTheBarSitsUnderTheSettingsTopBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { SettingsHub(section = SettingsSection.Providers, onSectionChange = {}, onBack = {}, content = { _, _ -> }) }
        val back = onNodeWithTag("settings_hub_back").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bar = onNodeWithTag("activity_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bar.top >= back.bottom, "the bar starts at ${bar.top}, inside the top bar (${back.bottom})")
        // In the Scaffold's top bar slot, so the page starts below it rather than under it.
        val firstRow = onNodeWithTag("settings_row_providers").getUnclippedBoundsInRoot()
        assertTrue(firstRow.top >= bar.bottom, "the first row (${firstRow.top}) starts above the bar's bottom ${bar.bottom}")
    }

    @Test fun onAPhoneTheBarSitsUnderAPagesOwnTopBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { AppUpdateScreen(onBack = {}, standalone = true, updater = FakeAppUpdater()) }
        val back = onNodeWithTag("app_update_back").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bar = onNodeWithTag("activity_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bar.top >= back.bottom, "the bar starts at ${bar.top}, inside the top bar (${back.bottom})")
        val page = onNodeWithTag("app_update_screen").getUnclippedBoundsInRoot()
        assertTrue(page.top >= bar.bottom, "the page (${page.top}) starts above the bar's bottom ${bar.bottom}")
    }

    @Test fun aWideHostHasNoPhoneBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        wide(center) { PhoneActivityBar() }
        onNodeWithTag("activity_bar").assertDoesNotExist()
    }

    // ── the list it opens ──────────────────────────────────────────────────────────────────────

    @Test fun onAPhoneATapOpensTheListInASheet() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { PhoneActivityBar() }
        onNodeWithTag("activity_list").assertDoesNotExist()
        onNodeWithTag("activity_bar").performClick()
        waitFor { onNodeWithTag("activity_list").assertIsDisplayed() }
        onNodeWithTag("activity_sheet").assertExists()
        onNodeWithTag("activity_popover").assertDoesNotExist()
    }

    @Test fun onAWideHostWithAPointerATapOpensTheListAnchoredToTheBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        wide(center) { ActivityBarWithPanel() }
        onNodeWithTag("activity_bar").performClick()
        waitFor { onNodeWithTag("activity_list").assertIsDisplayed() }
        onNodeWithTag("activity_popover").assertExists()
        onNodeWithTag("activity_sheet").assertDoesNotExist()
    }

    @Test fun theListsCancelAndRetryReachTheDownload() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { PhoneActivityBar() }
        onNodeWithTag("activity_bar").performClick()
        waitFor { onNodeWithTag("activity_cancel").assertIsDisplayed() }
        onNodeWithTag("activity_cancel").performClick()
        waitForIdle()
        assertEquals(DownloadState.Cancelled, center.items.value.single().state)
        waitFor { onNodeWithTag("activity_retry").assertIsDisplayed() }
        onNodeWithTag("activity_retry").performClick()
        waitForIdle()
        assertEquals(DownloadState.Running(42, 100), center.items.value.single().state)
    }

    @Test fun aFinishedFilesOpenIsHandedToTheHost() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val center = DownloadCenter(scope).apply { finished("att:h/2", "notes.pdf") }
        phone(center, platform) { PhoneActivityBar() }
        onNodeWithTag("activity_bar").performClick()
        waitFor { onNodeWithTag("activity_open").assertIsDisplayed() }
        onNodeWithTag("activity_open").performClick()
        settle { platform.files.openedFiles.isNotEmpty() }
        assertEquals(listOf("notes.pdf"), platform.files.openedFiles)
    }

    @Test fun aFinishedAppUpdateIsInstalledByTheUpdater() = runDesktopComposeUiTest {
        val updater = FakeAppUpdater()
        val platform = FakePlatform().apply { updates = updater }
        val installer = DownloadedInstaller(location = "/tmp/supermux_1.2.0.deb", kind = "deb")
        val center = DownloadCenter(scope).apply {
            finished("app-update:1.2.0", "Supermux 1.2.0", DownloadPurpose.Installer, handle = installer)
        }
        phone(center, platform) { PhoneActivityBar() }
        onNodeWithTag("activity_bar").performClick()
        waitFor { onNodeWithTag("activity_install").assertIsDisplayed() }
        onNodeWithTag("activity_install").performClick()
        settle { updater.installed.isNotEmpty() }
        assertEquals(listOf(installer), updater.installed)
    }

    @Test fun theBarsClearForgetsWhatHasEnded() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { finished("att:h/2", "notes.pdf") }
        phone(center) { PhoneActivityBar() }
        onNodeWithTag("activity_bar_clear").performClick()
        waitForIdle()
        assertEquals(emptyList(), center.items.value)
        onNodeWithTag("activity_bar").assertDoesNotExist()
    }

    // ── the wide sidebar ───────────────────────────────────────────────────────────────────────

    @Test fun onAWideHostTheBarSitsInTheSidebarFooterAboveTheUpdateCard() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val fleet = testFleet(testHostStore())
        try {
            fleet.downloads.running()
            val platform = FakePlatform().apply { updates = FakeAppUpdater(release = RELEASE) }
            setPlatformContent(platform) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) { SupermuxApp(fleet = fleet, ui = ShellUiState()) }
            }
            waitForIdle()
            val bar = onNodeWithTag("activity_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
            val card = onNodeWithTag("app_update_card").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bar.bottom <= card.top, "the bar ends at ${bar.bottom}, below the update card's top ${card.top}")
            // In the sidebar's footer, edge to edge with its sheet, not across the window.
            val footer = onNodeWithTag("sidebar_footer").getUnclippedBoundsInRoot()
            assertTrue(bar.left >= footer.left - 1.dp && bar.right <= footer.right + 1.dp, "the bar ($bar) is outside the footer ($footer)")
        } finally {
            fleet.close()
        }
    }

    @Test fun theCollapsedSidebarsRailShowsAnIconThatOpensTheList() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val fleet = testFleet(testHostStore())
        try {
            setPlatformContent {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    SupermuxApp(fleet = fleet, ui = ShellUiState().apply { sidebarCollapsed = true })
                }
            }
            waitForIdle()
            // Nothing listed, no icon.
            onNodeWithTag("activity_rail").assertDoesNotExist()
            fleet.downloads.running()
            waitForIdle()
            val icon = onNodeWithTag("activity_rail").assertIsDisplayed().getUnclippedBoundsInRoot()
            val settings = onNodeWithTag("rail_settings").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(icon.bottom <= settings.top, "the icon ends at ${icon.bottom}, below the rail's settings (${settings.top})")
            onNodeWithTag("activity_rail").performClick()
            waitFor { onNodeWithTag("activity_list").assertIsDisplayed() }
            onNodeWithTag("activity_popover").assertExists()
        } finally {
            fleet.close()
        }
    }

    // ── the shell ──────────────────────────────────────────────────────────────────────────────

    @Test fun aDownloadThatFinishesWithNobodyWaitingSaysSo() = runDesktopComposeUiTest {
        val fleet = testFleet(testHostStore())
        try {
            val platform = FakePlatform()
            setPlatformContent(platform, pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
                SupermuxApp(fleet = fleet, ui = ShellUiState())
            }
            waitForIdle()
            val gate = CompletableDeferred<Unit>()
            val source = object : DownloadSource {
                override suspend fun fetch(progress: (Long, Long?) -> Unit): DownloadedFile {
                    gate.await()
                    return DownloadedFile("a.pdf", "application/pdf", 3, "/tmp/a.pdf")
                }
            }
            // The screen that started it is gone before it finishes.
            fleet.downloads.start("att:h/f1", "a.pdf", "application/pdf", DownloadPurpose.File, source).release()
            gate.complete(Unit)
            settle { platform.notices.shown.any { it.endsWith("downloaded") } }
            assertEquals(listOf("a.pdf downloaded"), platform.notices.shown.filter { it.endsWith("downloaded") })
        } finally {
            fleet.close()
        }
    }

    // Another window of the app (desktop's `Window`, Android's activity, an iPad scene) is a composition of its own,
    // outside the shell's providers: the workspace it shows lends it the fleet's center.
    @Test fun aWorkspacesBindLendsTheFleetsDownloadsToItsOtherWindows() = runDesktopComposeUiTest {
        val fleet = testFleet(oneWorkspaceStore())
        try {
            val ui = ShellUiState().apply { selectedId = "s1" }
            setPlatformContent { SupermuxApp(fleet = fleet, ui = ui) }
            waitForIdle()
            val bind = assertNotNull(ui.panesBindFor("w1"))
            assertSame(fleet.downloads, bind.downloads)
        } finally {
            fleet.close()
        }
    }

    // ── outside a fleet ────────────────────────────────────────────────────────────────────────

    @Test fun insideAFleetAScreensDownloadsAreTheFleets() = runDesktopComposeUiTest {
        val fleets = DownloadCenter(scope)
        var got: DownloadCenter? = null
        setPlatformContent { CompositionLocalProvider(LocalDownloads provides fleets) { got = rememberDownloads() } }
        waitForIdle()
        assertSame(fleets, got)
    }

    // A test or a preview: today's lifetime, the download ends with the screen that holds it.
    @Test fun outsideAFleetADownloadLivesAsLongAsTheScreen() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var got: DownloadCenter? = null
        setPlatformContent { if (shown) got = rememberDownloads() }
        waitForIdle()
        val center = assertNotNull(got)
        center.running()
        settle { center.items.value.single().state == DownloadState.Running(42, 100) }
        assertEquals(DownloadState.Running(42, 100), center.items.value.single().state)
        shown = false
        settle { center.items.value.single().state !is DownloadState.Running }
        assertEquals(DownloadState.Cancelled, center.items.value.single().state)
    }

    private fun oneWorkspaceStore(): HostStore = testHostStore().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v1").toDto(),
                        views = listOf(
                            ViewDto(
                                id = "v1", workspaceId = "w1", kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        val RELEASE = ClientUpdateStatus(
            currentVersion = "1.1.0",
            latestVersion = "1.2.0",
            updateAvailable = true,
            downloadUrl = "https://example.test/supermux_1.2.0.deb",
            canInstall = true,
        )
    }
}
