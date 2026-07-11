package dev.supermux.desktop.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Compose UI tests for [EditorPanel] via the KCEF-free seams: [kcefStateFlow] is a MutableStateFlow
 * the test drives (never Ready → no browser is ever built) and `onEnsureInit = {}` so KCEF init never
 * fires. This exercises the pure-Compose shell (tree / tabs / empty / stale banner) and the engine
 * state machine's degraded surfaces (Downloading strip, native fallback) without booting Chromium.
 */
@OptIn(ExperimentalTestApi::class)
class EditorPanelTest {

    private val tree = listOf(
        FsEntry(name = "notes.txt", type = "file"),
        FsEntry(name = "src", type = "dir"),
    )

    /** A panel wired to in-memory fs fakes + a caller-controlled KCEF state / fs-change stream. */
    private fun ComposeContent(
        kcef: MutableStateFlow<KcefState>,
        fsChanges: MutableSharedFlow<ServerFrame.FsChanged>,
        readBody: String = "hello world",
    ): @androidx.compose.runtime.Composable () -> Unit = {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            EditorPanel(
                sessionId = "s1",
                workdir = "/w/s1",
                fsList = { tree },
                fsRead = { path -> Result.success("$readBody // $path") },
                fsWrite = { _, _ -> true },
                fsSearch = { emptyList<FsSearchResult>() },
                fsChanges = fsChanges,
                kcefStateFlow = kcef,
                onEnsureInit = {},
            )
        }
    }

    @Test
    fun renders_the_tree_and_the_empty_state() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent(ComposeContent(kcef, MutableSharedFlow()))

        onNodeWithTag("editor_tree").assertIsDisplayed()
        onNodeWithTag("editor_empty").assertIsDisplayed()
        onNodeWithText("notes.txt").assertIsDisplayed() // a tree node
    }

    @Test
    fun opening_a_file_from_the_tree_shows_a_tab_and_clears_the_empty_state() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent(ComposeContent(kcef, MutableSharedFlow()))

        onNodeWithText("notes.txt").performClick()
        // Empty prompt is gone → a tab opened (the tab strip renders the same filename as a chip).
        onNodeWithTag("editor_empty").assertDoesNotExist()
    }

    @Test
    fun stale_banner_appears_when_the_active_file_changes_on_disk() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        val fsChanges = MutableSharedFlow<ServerFrame.FsChanged>(extraBufferCapacity = 8)
        setContent(ComposeContent(kcef, fsChanges))

        onNodeWithText("notes.txt").performClick() // open it so there's an active tab
        onNodeWithTag("editor_stale_banner").assertDoesNotExist()

        runOnIdle { fsChanges.tryEmit(ServerFrame.FsChanged(session = "s1", paths = listOf("notes.txt"))) }
        onNodeWithTag("editor_stale_banner").assertIsDisplayed()
        onNodeWithTag("editor_reload").assertIsDisplayed()
    }

    @Test
    fun a_change_for_another_session_does_not_raise_the_banner() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        val fsChanges = MutableSharedFlow<ServerFrame.FsChanged>(extraBufferCapacity = 8)
        setContent(ComposeContent(kcef, fsChanges))

        onNodeWithText("notes.txt").performClick()
        runOnIdle { fsChanges.tryEmit(ServerFrame.FsChanged(session = "other", paths = listOf("notes.txt"))) }
        onNodeWithTag("editor_stale_banner").assertDoesNotExist()
    }

    @Test
    fun error_kcef_state_renders_the_native_fallback_editor() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Error("boom"))
        setContent(ComposeContent(kcef, MutableSharedFlow()))

        onNodeWithText("notes.txt").performClick() // give the fallback some content
        onNodeWithTag("editor_native_fallback").assertIsDisplayed()
    }

    @Test
    fun downloading_kcef_state_shows_the_progress_strip() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Downloading(42f))
        setContent(ComposeContent(kcef, MutableSharedFlow()))

        onNodeWithTag("editor_downloading").assertIsDisplayed()
    }

    // A pending reveal must SURVIVE the engine-less window (T5's chat-tap open typically arrives
    // before KCEF is Ready): consuming it with engine == null would silently drop the line jump.
    @Test
    fun reveal_is_not_consumed_while_the_engine_is_absent() = runComposeUiTest {
        var consumed = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorSurface(
                    kcefState = KcefState.Idle, // no engine will ever exist in this test
                    content = "text",
                    filename = "a.kt",
                    lineWrap = true,
                    fontSize = 13,
                    scrollTop = 0,
                    revealLine = 5 to null,
                    onChange = {},
                    onSave = {},
                    onRevealConsumed = { consumed = true },
                    onFontSize = {},
                    onEnsureInit = {},
                )
            }
        }
        waitForIdle()
        assertFalse(consumed) // still pending — delivered when the engine arrives
    }

    // ── pendingOpen (M3-T5, chat-tap → editor-at-line) ─────────────────────────────────
    // SessionDetail hands EditorPanel a [PendingEditorOpen] via this seam; EditorPanel's own
    // LaunchedEffect(pendingOpen) (Android EditorScreen:223 parity) must reveal the file and consume
    // the request via [onPendingOpenConsumed] exactly once — never re-firing on a later, unrelated
    // recomposition (verified by an extra `waitForIdle()` pass after the initial delivery).

    @Test
    fun pending_open_opens_a_tab_for_the_delivered_path_and_is_consumed_exactly_once() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        var consumedCount = 0
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree },
                    fsRead = { path -> Result.success("hello // $path") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                    pendingOpen = PendingEditorOpen("README.md", 3, null),
                    onPendingOpenConsumed = { consumedCount++ },
                )
            }
        }
        onNodeWithTag("editor_empty").assertDoesNotExist() // a tab opened, not the empty prompt
        onNodeWithText("README.md").assertIsDisplayed() // the tab chip for the delivered path
        assertEquals(1, consumedCount)

        // A later idle pass (no pendingOpen change) must NOT re-fire the consume callback — the
        // LaunchedEffect is keyed on `pendingOpen`, which hasn't changed.
        waitForIdle()
        assertEquals(1, consumedCount)
    }

    @Test
    fun a_null_pendingOpen_never_calls_onPendingOpenConsumed() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        var consumedCount = 0
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree },
                    fsRead = { path -> Result.success("hello // $path") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                    pendingOpen = null,
                    onPendingOpenConsumed = { consumedCount++ },
                )
            }
        }
        waitForIdle()
        assertEquals(0, consumedCount)
        onNodeWithTag("editor_empty").assertIsDisplayed() // nothing opened
    }

    // Tab-switch scroll capture wiring (Android EditorScreen:406-408/:216 parity): opening a second
    // file (reveal path) and clicking a tab chip (select path) must each read the outgoing scroll
    // through the injected EditorScrollReader seam.
    @Test
    fun switching_files_captures_the_outgoing_scroll_via_the_reader_seam() = runComposeUiTest {
        var reads = 0
        val reader = EditorScrollReader().apply { read = { reads++; it(42) } }
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree + FsEntry(name = "README.md", type = "file") },
                    fsRead = { path -> Result.success("body // $path") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                    scrollReader = reader,
                )
            }
        }
        onNodeWithText("notes.txt").performClick() // first open: no outgoing tab → no read
        assertEquals(0, reads)

        onNodeWithText("README.md").performClick() // reveal path: captures notes.txt's scroll
        assertEquals(1, reads)

        // Hide the tree so the tab chip is the only "notes.txt" text node, then select it.
        onNodeWithTag("editor_tree_toggle").performClick()
        onNodeWithText("notes.txt").performClick() // select path: captures README.md's scroll
        assertEquals(2, reads)
    }

    // ── Markdown preview toggle (M4g-1) ─────────────────────────────────────────────────
    // The pure isMarkdownPath/showPreview derivation is unit-tested directly in
    // EditorPanelMarkdownTest; these exercise the toggle + the conditional SWAP (EditorSurface is not
    // composed while previewing — see EditorPanel.kt's call-site comment for why an overlay doesn't
    // work on this platform) wired into the hosted panel (still KCEF-free: kcefStateFlow stays Idle
    // so EditorSurface never builds a browser).

    @Test
    fun a_text_tab_does_not_show_the_preview_toggle() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent(ComposeContent(kcef, MutableSharedFlow()))

        onNodeWithText("notes.txt").performClick() // active tab is notes.txt (not markdown)
        onNodeWithTag("editor_preview_toggle").assertDoesNotExist()
    }

    @Test
    fun a_markdown_tab_shows_the_preview_toggle() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree + FsEntry(name = "README.md", type = "file") },
                    fsRead = { path -> Result.success("# Heading\n\nBody text.") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                )
            }
        }

        onNodeWithText("README.md").performClick() // active tab is README.md (markdown)
        onNodeWithTag("editor_preview_toggle").assertIsDisplayed()
    }

    @Test
    fun toggling_preview_on_a_markdown_tab_shows_the_rendered_overlay_then_hides_it_again() = runComposeUiTest {
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree + FsEntry(name = "README.md", type = "file") },
                    fsRead = { path -> Result.success("# Heading\n\nBody text.") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                )
            }
        }

        onNodeWithText("README.md").performClick()
        onNodeWithTag("editor_preview").assertDoesNotExist() // off by default

        onNodeWithTag("editor_preview_toggle").performClick()
        onNodeWithTag("editor_preview").assertIsDisplayed()
        onNodeWithText("Heading").assertIsDisplayed() // MarkdownBody rendered the heading block
        onNodeWithText("Body text.").assertIsDisplayed() // ...and the prose block

        onNodeWithTag("editor_preview_toggle").performClick() // toggle back off
        onNodeWithTag("editor_preview").assertDoesNotExist()
    }

    @Test
    fun preview_mode_swaps_the_editor_surface_out_instead_of_overlaying_it() = runComposeUiTest {
        // The whole point of the swap (vs. the original overlay) is that EditorSurface is not
        // composed at all while previewing — an overlay would leave "editor_web_area" composed
        // (just visually covered), which is exactly what made the KCEF SwingPanel occlude it on a
        // live renderer. Idle KCEF state still routes through the `else` (web-area) branch of
        // EditorSurface's `when`, so its testTag is the right thing to assert absent here.
        val kcef = MutableStateFlow<KcefState>(KcefState.Idle)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree + FsEntry(name = "README.md", type = "file") },
                    fsRead = { path -> Result.success("# Heading\n\nBody text.") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                )
            }
        }

        onNodeWithText("README.md").performClick()
        onNodeWithTag("editor_web_area").assertIsDisplayed() // editing: the surface is composed

        onNodeWithTag("editor_preview_toggle").performClick()
        onNodeWithTag("editor_web_area").assertDoesNotExist() // previewing: swapped out, not just covered
        onNodeWithTag("editor_preview").assertIsDisplayed()

        onNodeWithTag("editor_preview_toggle").performClick()
        onNodeWithTag("editor_web_area").assertIsDisplayed() // back to editing: surface recomposed
    }

    @Test
    fun an_unsaved_edit_survives_a_preview_then_edit_round_trip() = runComposeUiTest {
        // The swap trades away "keep KCEF warm" for correctness — this proves that trade doesn't
        // also cost the user their in-progress edit. activeTab.content lives in EditorState (panel-
        // level remember, independent of EditorSurface's composition), so it must still be there,
        // still dirty, after EditorSurface is torn down and rebuilt around the preview toggle. KCEF
        // itself isn't drivable headlessly in a unit test, so this exercises the SAME onChange sink
        // (BasicTextField.onValueChange -> EditorState.updateContent) via the native-fallback path
        // (KcefState.Error), which cm6's onChange feeds identically in production.
        val kcef = MutableStateFlow<KcefState>(KcefState.Error("boom"))
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    sessionId = "s1",
                    workdir = "/w/s1",
                    fsList = { tree + FsEntry(name = "README.md", type = "file") },
                    fsRead = { path -> Result.success("# Heading\n\nBody text.") },
                    fsWrite = { _, _ -> true },
                    fsSearch = { emptyList<FsSearchResult>() },
                    kcefStateFlow = kcef,
                    onEnsureInit = {},
                )
            }
        }

        onNodeWithText("README.md").performClick()
        onNodeWithTag("editor_native_input").performTextReplacement("# Heading\n\nEDITED body text.")
        onNodeWithTag("editor_save").assertIsEnabled() // dirty: the edit hasn't been saved

        onNodeWithTag("editor_preview_toggle").performClick() // preview: rendered from the EDITED content
        onNodeWithText("EDITED body text.").assertIsDisplayed()

        onNodeWithTag("editor_preview_toggle").performClick() // back to editing
        onNodeWithTag("editor_native_input").assertTextContains("EDITED body text.", substring = true)
        onNodeWithTag("editor_save").assertIsEnabled() // still dirty — the edit was never lost or auto-saved
    }
}
