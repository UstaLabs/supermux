package dev.supermux.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
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

@OptIn(ExperimentalTestApi::class)
class FileTreeViewTest {
    private fun service(sent: MutableList<ClientFrame>) = FileSystemService(
        BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
        send = { synchronized(sent) { sent += it } },
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    private fun host(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    private fun sentCopy(sent: MutableList<ClientFrame>) = synchronized(sent) { sent.toList() }

    @Test fun rendersRootExpandsFoldersAndOpensFiles() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        val opened = mutableListOf<String>()
        setContent(host { FileTreeView(fs, view, onOpenFile = { opened += it }) })
        waitForIdle()
        assertTrue(ClientFrame.FsSub("/w") in sentCopy(sent))
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"), FsEntry(name = "a.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:src").assertIsDisplayed()
        onNodeWithTag("tree_row:src").performClick()
        waitForIdle()
        assertTrue(ClientFrame.FsSub("/w/src") in sentCopy(sent))
        assertEquals("/w/src", view.selected)
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "b.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:b.kt").performClick()
        waitForIdle()
        assertEquals(listOf("/w/src/b.kt"), opened)
        assertEquals("/w/src/b.kt", view.selected)
        // Collapsing unsubscribes (grace 0).
        onNodeWithTag("tree_row:src").performClick()
        waitForIdle()
        assertTrue(ClientFrame.FsUnsub("/w/src") in sentCopy(sent))
        onNodeWithTag("tree_row:b.kt").assertDoesNotExist()
    }

    @Test fun liveUpdatesAndGonePrunes() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w").apply { expand("/w/src") }
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"))))
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "old.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:old.kt").assertIsDisplayed()
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "2", entries = listOf(FsEntry(name = "new.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:new.kt").assertIsDisplayed()
        onNodeWithTag("tree_row:old.kt").assertDoesNotExist()
        fs.onFrame(ServerFrame.FsGone("/w/src"))
        waitForIdle()
        assertTrue("/w/src" !in view.expanded)
    }

    @Test fun onlyVisibleRowsAreComposed() = runComposeUiTest {
        val fs = service(mutableListOf())
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = (0 until 5000).map { FsEntry(name = "f%04d".format(it), type = "file") }))
        waitForIdle()
        onNodeWithTag("tree_row:f0000").assertIsDisplayed()
        onNodeWithTag("tree_row:f4999").assertDoesNotExist()
    }

    @Test fun aFailedFolderShowsItsErrorAndAClickRetries() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w").apply { expand("/w/locked") }
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "locked", type = "dir"))))
        fs.onFrame(ServerFrame.FsErr(path = "/w/locked", code = "EACCES", message = "permission denied"))
        waitForIdle()
        onNodeWithText("permission denied").assertIsDisplayed()
        val before = sentCopy(sent).count { it == ClientFrame.FsSub("/w/locked") }
        onNodeWithTag("tree_row:locked").performClick()
        waitForIdle()
        assertEquals(before + 1, sentCopy(sent).count { it == ClientFrame.FsSub("/w/locked") })
        assertTrue("/w/locked" in view.expanded)
        fs.onFrame(ServerFrame.FsDir(path = "/w/locked", version = "1", entries = listOf(FsEntry(name = "ok.txt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:ok.txt").assertIsDisplayed()
    }

    @Test fun rootStates_emptyAndFailedWithRetry() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsErr(path = "/w", code = "ENOENT", message = "no such folder"))
        waitForIdle()
        onNodeWithText("no such folder").assertIsDisplayed()
        onNodeWithTag("editor_tree_retry").performClick()
        waitForIdle()
        assertEquals(2, sentCopy(sent).count { it == ClientFrame.FsSub("/w") })
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = emptyList()))
        waitForIdle()
        onNodeWithText("Empty folder").assertIsDisplayed()
        onNodeWithTag("editor_tree").assertIsDisplayed()
    }

    @Test fun activePathIsRevealed() = runComposeUiTest {
        val fs = service(mutableListOf())
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}, activePath = "/w/src/deep/x.kt") })
        waitForIdle()
        assertTrue("/w/src" in view.expanded && "/w/src/deep" in view.expanded)
        assertEquals("/w/src/deep/x.kt", view.selected)
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = (0 until 200).map { FsEntry(name = "a%03d".format(it), type = "file") } + FsEntry(name = "src", type = "dir")))
        fs.onFrame(ServerFrame.FsDir(path = "/w/src", version = "1", entries = listOf(FsEntry(name = "deep", type = "dir"))))
        fs.onFrame(ServerFrame.FsDir(path = "/w/src/deep", version = "1", entries = listOf(FsEntry(name = "x.kt", type = "file"))))
        waitForIdle()
        onNodeWithTag("tree_row:x.kt").assertIsDisplayed()
    }

    @Test fun activePathSubscribesItsAncestorsAndSelectsIt() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}, activePath = "/w/src/ui/A.kt") })
        waitForIdle()
        val frames = sentCopy(sent)
        assertTrue(ClientFrame.FsSub("/w/src") in frames && ClientFrame.FsSub("/w/src/ui") in frames)
        assertEquals("/w/src/ui/A.kt", view.selected)
    }

    @Test fun revealActiveOffLeavesTheTreeAlone() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}, activePath = "/w/src/ui/A.kt", revealActive = false) })
        waitForIdle()
        assertTrue(ClientFrame.FsSub("/w/src") !in sentCopy(sent))
        assertEquals(emptySet(), view.expanded)
        assertEquals(null, view.selected)
    }

    @Test fun theChevronCollapsesAFailedFolderWhileTheRowRetries() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w").apply { expand("/w/locked") }
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "locked", type = "dir"))))
        fs.onFrame(ServerFrame.FsErr(path = "/w/locked", code = "EACCES", message = "permission denied"))
        waitForIdle()
        onNodeWithTag("tree_row:locked").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        onNodeWithTag("tree_chevron:locked", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue("/w/locked" !in view.expanded)
        onNodeWithText("permission denied").assertDoesNotExist()
        onNodeWithTag("tree_row:locked").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test fun aFailedRootRefreshShowsAStripAboveTheKnownRows() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = service(sent)
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "a.kt", type = "file"))))
        fs.onFrame(ServerFrame.FsErr(path = "/w", code = "EIO", message = "read failed"))
        waitForIdle()
        onNodeWithTag("editor_tree_error").assertIsDisplayed()
        onNodeWithText("read failed").assertIsDisplayed()
        onNodeWithTag("tree_row:a.kt").assertIsDisplayed()
        val before = sentCopy(sent).count { it is ClientFrame.FsSub && it.path == "/w" }
        onNodeWithTag("editor_tree_retry").performClick()
        waitForIdle()
        assertEquals(before + 1, sentCopy(sent).count { it is ClientFrame.FsSub && it.path == "/w" })
        onNodeWithTag("editor_tree_error").assertDoesNotExist() // the retry is Loading(previous) now
        onNodeWithTag("tree_row:a.kt").assertIsDisplayed()
    }

    @Test fun compactRowsAreThumbSized() = runComposeUiTest {
        val fs = service(mutableListOf())
        val view = TreeViewState("/w")
        setContent(host { FileTreeView(fs, view, onOpenFile = {}, compact = true) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"), FsEntry(name = "a.kt", type = "file", git = "M"))))
        waitForIdle()
        onNodeWithTag("tree_row:a.kt").assertHeightIsAtLeast(44.dp)
        onNodeWithTag("tree_row:src").assertHeightIsAtLeast(44.dp)
        onNodeWithTag("tree_row:a.kt").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        onNodeWithContentDescription("Modified").assertExists()
    }

    @Test fun badges() {
        assertEquals("K", fileBadge("a.kt", false).label)
        assertEquals("TS", fileBadge("A.TSX", false).label)
        assertEquals("•", fileBadge(".gitignore", false).label)
        assertEquals("X", fileBadge("a.xyz", false).label)
        assertEquals("·", fileBadge("Makefile", false).label)
    }
}
