package dev.supermux.desktop.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

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
}
