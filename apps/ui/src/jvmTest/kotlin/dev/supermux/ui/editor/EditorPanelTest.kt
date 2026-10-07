package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composite panel — the phone/SessionDetail shape of the editor, on the native editor.
 */
@OptIn(ExperimentalTestApi::class)
class EditorPanelTest {

    private val backs = mutableListOf<Boolean>()

    private val fs = FileSystemService(
        BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
        send = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    private fun host(
        widthClass: WindowWidthClass,
        pendingOpen: PendingEditorOpen? = null,
        read: (String) -> Result<String> = { Result.success("hello") },
    ): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalWindowWidthClass provides widthClass,
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                EditorPanel(
                    state = EditorPanelState(
                        sessionId = "s1",
                        workdir = "/w",
                    ),
                    actions = EditorPanelActions(
                        fileSystem = fs,
                        fsRead = { read(it) },
                        fsWrite = { _, _ -> true },
                        fsSearch = { emptyList() },
                    ),
                    onConsumesBackChange = { backs += it },
                    pendingOpen = pendingOpen,
                    modifier = Modifier,
                )
            }
        }
    }

    @Test
    fun under_compact_the_tree_opens_as_a_drawer_and_back_closes_it() = runComposeUiTest {
        setContent(host(WindowWidthClass.Compact))
        waitForIdle()
        // A phone starts with the drawer shut, so the panel consumes no back.
        assertEquals(false, backs.last())

        onNodeWithTag("editor_tree_toggle").performClick()
        waitForIdle()
        onNodeWithTag("editor_tree_drawer").assertIsDisplayed()
        assertEquals(true, backs.last(), "an open drawer must claim the back gesture")

        // Dismissing the drawer is what the panel's BackHandler does — it sets `treeVisible`
        // false — and the scrim runs the same line. The gesture itself cannot be fired here:
        // Compose Multiplatform's BackHandler listens on a NavigationEventDispatcher that only a
        // real window (an Android activity) provides, and the composition local that would let a
        // test inject one is @InternalComposeUiApi.
        onNodeWithTag("editor_tree_scrim").performClick()
        waitForIdle()
        onNodeWithTag("editor_tree_drawer").assertDoesNotExist()
        assertEquals(false, backs.last(), "closing the drawer must hand back back")
        assertTrue(backs.contains(true) && backs.last() == false, "the contract is a true→false toggle")
    }

    @Test
    fun outside_compact_the_tree_is_a_side_pane_and_never_a_drawer() = runComposeUiTest {
        setContent(host(WindowWidthClass.Expanded))
        waitForIdle()

        onNodeWithTag("editor_tree_pane").assertIsDisplayed()
        onNodeWithTag("editor_tree_drawer").assertDoesNotExist()
        assertEquals(false, backs.last(), "a side pane never consumes back")
    }

    @Test
    fun clicking_a_file_in_the_tree_opens_it_by_its_workdir_relative_path() = runComposeUiTest {
        setContent(host(WindowWidthClass.Expanded))
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "a.kt", type = "file"))))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performClick()
        waitForIdle()
        onNodeWithTag("editor_tab_a.kt").assertIsDisplayed()
    }

    @Test
    fun a_pending_open_opens_the_file() = runComposeUiTest {
        setContent(host(WindowWidthClass.Expanded, pendingOpen = PendingEditorOpen("a.kt", 3, null)))
        waitForIdle()

        // The tab is the proof the open landed (the tree also names the file).
        onNodeWithTag("editor_tab_a.kt").assertIsDisplayed()
    }

    private fun snap(version: String, vararg files: Pair<String, Long>) =
        ServerFrame.FsDir(path = "/w", version = version, entries = files.map { (n, m) -> FsEntry(name = n, type = "file", mtime = m, size = 1) })

    /**
     * Since 1da9d459 a CLEAN open file just takes the new text from disk: the panel re-reads it
     * and shows no banner. Only a file the banner protects (unsaved edits, or one that can no
     * longer be read) raises it. The unsaved-edits half is pinned on [DocumentStore] by
     * FileStaleWatcherTest; these two pin the panel's own watcher wiring end to end.
     */
    @Test
    fun an_outside_change_to_a_clean_open_file_reloads_it_without_the_banner() = runComposeUiTest {
        val reads = mutableListOf<String>()
        var disk = "v1"
        setContent(
            host(
                WindowWidthClass.Expanded,
                pendingOpen = PendingEditorOpen("a.kt", null, null),
                read = { reads += it; Result.success(disk) },
            ),
        )
        waitForIdle()
        fs.onFrame(snap("1", "a.kt" to 1, "b.kt" to 1))
        waitForIdle()
        val readsBefore = reads.size

        disk = "v2"
        fs.onFrame(snap("2", "a.kt" to 2, "b.kt" to 1))
        waitForIdle()

        // Re-read from disk once, and nothing to warn about.
        assertEquals(listOf("a.kt"), reads.drop(readsBefore))
        onNodeWithTag("editor_stale_banner").assertDoesNotExist()
    }

    @Test
    fun an_outside_change_the_panel_cannot_reread_raises_the_stale_banner() = runComposeUiTest {
        var gone = false
        setContent(
            host(
                WindowWidthClass.Expanded,
                pendingOpen = PendingEditorOpen("a.kt", null, null),
                read = { if (gone) Result.failure(RuntimeException("not found")) else Result.success("hello") },
            ),
        )
        waitForIdle()
        fs.onFrame(snap("1", "a.kt" to 1, "b.kt" to 1))
        waitForIdle()
        onNodeWithTag("editor_stale_banner").assertDoesNotExist()

        // Deleted (or otherwise unreadable) behind the open tab: the reload falls back to the banner.
        gone = true
        fs.onFrame(snap("2", "a.kt" to 2, "b.kt" to 1))
        waitForIdle()

        onNodeWithTag("editor_stale_banner").assertIsDisplayed()
    }

    @Test
    fun a_change_to_a_file_that_is_not_open_is_ignored() = runComposeUiTest {
        setContent(host(WindowWidthClass.Expanded, pendingOpen = PendingEditorOpen("a.kt", null, null)))
        waitForIdle()
        fs.onFrame(snap("1", "a.kt" to 1, "b.kt" to 1))
        waitForIdle()

        fs.onFrame(snap("2", "a.kt" to 1, "b.kt" to 7))
        waitForIdle()

        onNodeWithTag("editor_stale_banner").assertDoesNotExist()
    }
}
