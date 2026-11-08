package dev.supermux.ui.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
import dev.supermux.ui.session.SessionListFooter
import dev.supermux.ui.session.SessionListScreen
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.testFleet
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
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
 * Where the downloads live and what they are wired to (#95, design A): one more icon in the sidebar's bottom bar —
 * the same bar on a phone's Home and on a desktop — a ↓ in our own thin ring that fills while one runs, shown only
 * while the center lists something; the same ring on the collapsed sidebar's rail; the list it opens (our sheet on
 * a phone, a popover standing on the bar on a wide host with a pointer); nothing under a phone's top bars any more;
 * and the shell's notice for a download that finished with nobody waiting. In the app theme: these assert layout.
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

    /** The sidebar's bottom bar on its own: the host switch's place, the downloads, theme / usage / devices / settings. */
    @Composable
    private fun Footer() = SessionListFooter(
        appearance = AppearanceMode.LIGHT,
        onToggleTheme = {},
        onUsage = {},
        onDevices = {},
        onSettings = {},
    )

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

    /** The ring sits in the bottom bar's row of icons, before the bar's own (theme first). */
    private fun ComposeUiTest.assertTheRingIsInTheBottomBar() {
        val row = onNodeWithTag("sidebar_footer").assertIsDisplayed().getUnclippedBoundsInRoot()
        val ring = onNodeWithTag(FOOTER_RING).assertIsDisplayed().getUnclippedBoundsInRoot()
        val theme = onNodeWithTag("sidebar_footer_theme").getUnclippedBoundsInRoot()
        assertTrue(
            ring.top >= row.top && ring.bottom <= row.bottom && ring.left >= row.left && ring.right <= row.right,
            "the ring ($ring) is outside the bottom bar's row ($row)",
        )
        assertTrue(ring.right <= theme.left, "the ring (${ring.right}) does not come before the bar's own icons (${theme.left})")
    }

    // ── one more icon in the sidebar's bottom bar ──────────────────────────────────────────────

    @Test fun onAPhoneTheRingIsAnIconInHomesBottomBar() = runDesktopComposeUiTest(width = 420, height = 860) {
        val fleet = testFleet(testHostStore())
        try {
            fleet.downloads.running()
            setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) { SupermuxApp(fleet = fleet, ui = ShellUiState()) }
            }
            waitForIdle()
            assertTheRingIsInTheBottomBar()
        } finally {
            fleet.close()
        }
    }

    @Test fun onAWideHostTheRingIsAnIconInTheSidebarsBottomBar() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val fleet = testFleet(testHostStore())
        try {
            fleet.downloads.running()
            setPlatformContent(FakePlatform().apply { updates = FakeAppUpdater(release = RELEASE) }) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) { SupermuxApp(fleet = fleet, ui = ShellUiState()) }
            }
            waitForIdle()
            assertTheRingIsInTheBottomBar()
            // In the bar, under the update card's place, not a strip of its own above it.
            val card = onNodeWithTag("app_update_card").assertIsDisplayed().getUnclippedBoundsInRoot()
            val ring = onNodeWithTag(FOOTER_RING).getUnclippedBoundsInRoot()
            assertTrue(ring.top >= card.bottom, "the ring (${ring.top}) is above the update card's bottom (${card.bottom})")
        } finally {
            fleet.close()
        }
    }

    @Test fun withNothingListedTheBarHasNoRing() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope)
        wide(center) { Footer() }
        onNodeWithTag("sidebar_footer").assertIsDisplayed()
        onNodeWithTag(FOOTER_RING).assertDoesNotExist()
        // It comes with the first download.
        center.running()
        waitForIdle()
        onNodeWithTag(FOOTER_RING).assertIsDisplayed()
    }

    @Test fun aRunningDownloadFillsTheRing() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        wide(center) { Footer() }
        onNode(hasTestTag(FOOTER_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertIsDisplayed()
    }

    @Test fun onceNothingRunsTheRingIsASteadyGlyph() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { finished("att:h/2", "notes.pdf") }
        wide(center) { Footer() }
        onNodeWithTag(FOOTER_RING).assertIsDisplayed()
        onNode(hasTestTag(FOOTER_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertDoesNotExist()
        onNode(hasTestTag(FOOTER_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    }

    // ── nothing under a phone's top bars any more ──────────────────────────────────────────────

    // Ahmet's round 2: the downloads have one place, the sidebar's bottom bar. A chat, Settings and the pages are
    // honest: the sidebar is not on screen there, so neither are the downloads.

    @Test fun onAPhoneNothingSitsUnderHomesTopBar() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { SessionListScreen(home = "/home/u", activeId = null, onOpen = {}, standalone = true) }
        onNodeWithTag("session_list_logo_bar").assertIsDisplayed()
        onNode(hasText("big-video.mp4", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun onAPhoneAChatShowsNoDownloads() = runDesktopComposeUiTest {
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
        onNodeWithTag("chat_find_open").assertIsDisplayed()
        onNode(hasText("big-video.mp4", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun onAPhoneSettingsShowsNoDownloads() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { SettingsHub(section = SettingsSection.Providers, onSectionChange = {}, onBack = {}, content = { _, _ -> }) }
        onNodeWithTag("settings_hub_back").assertIsDisplayed()
        onNode(hasText("big-video.mp4", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    // ── the list it opens ──────────────────────────────────────────────────────────────────────

    @Test fun onAPhoneATapOpensTheListInASheet() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { Footer() }
        onNodeWithTag("activity_list").assertDoesNotExist()
        onNodeWithTag(FOOTER_RING).performClick()
        waitFor { onNodeWithTag("activity_list").assertIsDisplayed() }
        onNodeWithTag("activity_sheet").assertExists()
        onNodeWithTag("activity_popover").assertDoesNotExist()
    }

    @Test fun onAWideHostWithAPointerATapOpensThePopover() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        wide(center) { Footer() }
        onNodeWithTag(FOOTER_RING).performClick()
        waitFor { onNodeWithTag("activity_list").assertIsDisplayed() }
        onNodeWithTag("activity_popover").assertExists()
        onNodeWithTag("activity_sheet").assertDoesNotExist()
    }

    // The prototype's popover stands on the whole bottom bar — the update card's place included — along its start
    // edge, 6 dp above it.
    @Test fun thePopoverStandsOnTheWholeBarAlongItsStartEdge() {
        val gap = 6
        val inset = 6
        val position = OnTheBar(gap = gap, inset = inset)
        val bar = IntRect(left = 0, top = 600, right = 320, bottom = 900)
        val window = IntSize(1200, 900)
        val popover = IntSize(470, 260)
        assertEquals(IntOffset(inset, 600 - 260 - gap), position.calculatePosition(bar, window, LayoutDirection.Ltr, popover))
        // No room above: under the bar's top, clamped to the window.
        val low = IntRect(left = 0, top = 100, right = 320, bottom = 300)
        val placed = position.calculatePosition(low, window, LayoutDirection.Ltr, popover)
        assertTrue(placed.y >= 0 && placed.y + popover.height <= window.height, "placed at $placed, outside the window")
    }

    // Beside the collapsed rail, the popover grows up from the ring's tile, just past the rail's edge.
    @Test fun besideTheRailThePopoverGrowsUpFromTheTile() {
        val position = BesideTheTile(gap = 6, drop = 4, margin = 8)
        val tile = IntRect(left = 0, top = 700, right = 52, bottom = 734)
        val window = IntSize(1200, 900)
        val popover = IntSize(470, 260)
        assertEquals(IntOffset(52 + 6, 734 + 4 - 260), position.calculatePosition(tile, window, LayoutDirection.Ltr, popover))
        // Near the top of a short window it stays inside, a margin from the top.
        val high = IntRect(left = 0, top = 20, right = 52, bottom = 54)
        assertEquals(8, position.calculatePosition(high, window, LayoutDirection.Ltr, popover).y)
    }

    @Test fun theListsCancelAndRetryReachTheDownload() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { running() }
        phone(center) { Footer() }
        onNodeWithTag(FOOTER_RING).performClick()
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
        phone(center, platform) { Footer() }
        onNodeWithTag(FOOTER_RING).performClick()
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
        phone(center, platform) { Footer() }
        onNodeWithTag(FOOTER_RING).performClick()
        waitFor { onNodeWithTag("activity_install").assertIsDisplayed() }
        onNodeWithTag("activity_install").performClick()
        settle { updater.installed.isNotEmpty() }
        assertEquals(listOf(installer), updater.installed)
    }

    @Test fun theListsClearFinishedForgetsWhatHasEndedAndTheRingGoes() = runDesktopComposeUiTest {
        val center = DownloadCenter(scope).apply { finished("att:h/2", "notes.pdf") }
        phone(center) { Footer() }
        onNodeWithTag(FOOTER_RING).performClick()
        waitFor { onNodeWithTag("activity_clear").assertIsDisplayed() }
        onNodeWithTag("activity_clear").performClick()
        waitForIdle()
        assertEquals(emptyList(), center.items.value)
        waitFor { onNodeWithTag("activity_list").assertDoesNotExist() }
        onNodeWithTag(FOOTER_RING).assertDoesNotExist()
    }

    // ── the collapsed sidebar ──────────────────────────────────────────────────────────────────

    @Test fun theCollapsedSidebarsRailShowsTheRingThatOpensTheList() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val fleet = testFleet(testHostStore())
        try {
            setPlatformContent {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    SupermuxApp(fleet = fleet, ui = ShellUiState().apply { sidebarCollapsed = true })
                }
            }
            waitForIdle()
            // Nothing listed, no ring.
            onNodeWithTag(RAIL_RING).assertDoesNotExist()
            fleet.downloads.running()
            waitForIdle()
            val ring = onNodeWithTag(RAIL_RING).assertIsDisplayed().getUnclippedBoundsInRoot()
            onNode(hasTestTag(RAIL_RING) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertExists()
            // On the rail's own tone, just above its bottom sheet (the host and the settings), as in the prototype.
            val sheet = onNodeWithTag("sidebar_rail_footer").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(ring.bottom <= sheet.top, "the ring ends at ${ring.bottom}, inside the rail's bottom sheet (${sheet.top})")
            onNodeWithTag(RAIL_RING).performClick()
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
        /** The ring in the sidebar's bottom bar, next to the bar's own icons (`sidebar_footer_theme`, …). */
        const val FOOTER_RING = "sidebar_footer_downloads"

        /** The same ring on the collapsed sidebar's rail, next to `rail_settings`. */
        const val RAIL_RING = "rail_downloads"

        val RELEASE = ClientUpdateStatus(
            currentVersion = "1.1.0",
            latestVersion = "1.2.0",
            updateAvailable = true,
            downloadUrl = "https://example.test/supermux_1.2.0.deb",
            canInstall = true,
        )
    }
}
