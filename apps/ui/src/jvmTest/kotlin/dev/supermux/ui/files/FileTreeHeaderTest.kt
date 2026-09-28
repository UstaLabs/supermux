package dev.supermux.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
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
class FileTreeHeaderTest {

    @Test fun crumbsUnderHomeStartAtTilde() {
        val b = breadcrumbsOf("/home/u/p", "/home/u")
        assertEquals(false, b.truncated)
        assertEquals(listOf(Crumb("~", "/home/u"), Crumb("p", "/home/u/p")), b.crumbs)
    }

    @Test fun crumbsOutsideHomeStartAtSlashAndKeepTheLastThree() {
        assertEquals(Breadcrumbs(false, listOf(Crumb("/", "/"))), breadcrumbsOf("/", "/home/u"))
        val b = breadcrumbsOf("/srv/a/b/c", null)
        assertTrue(b.truncated)
        assertEquals(listOf(Crumb("a", "/srv/a"), Crumb("b", "/srv/a/b"), Crumb("c", "/srv/a/b/c")), b.crumbs)
    }

    private fun host(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    @Test fun crumbsReRootCollapseAllAndRefreshWork() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val fs = FileSystemService(
            BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
            send = { synchronized(sent) { sent += it } },
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 0,
        )
        val view = TreeViewState("/home/u/p/q")
        view.expand("/home/u/p/q/src")
        setContent(host { FileTreeHeader(view, fs) })
        waitForIdle()
        onNodeWithTag("tree_breadcrumb:~").assertIsDisplayed()
        onNodeWithTag("tree_workspace_chip").assertDoesNotExist()

        // Refresh re-sends fs_sub for the subscribed folders the tree shows.
        val sub = fs.subscribe("/home/u/p/q")
        synchronized(sent) { sent.clear() }
        onNodeWithTag("tree_refresh").performClick()
        waitForIdle()
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/home/u/p/q")), synchronized(sent) { sent.toList() })
        sub.close()

        onNodeWithTag("tree_collapse_all").performClick()
        waitForIdle()
        assertEquals(emptySet(), view.expanded)

        view.expand("/home/u/p/x")
        onNodeWithTag("tree_breadcrumb:p").performClick()
        waitForIdle()
        assertEquals("/home/u/p", view.rootPath)
        assertEquals(emptySet(), view.expanded)
        onNodeWithText("Workspace").assertIsDisplayed()
        onNodeWithTag("tree_workspace_chip").performClick()
        waitForIdle()
        assertEquals("/home/u/p/q", view.rootPath)
    }

    @Test fun theMenuTogglesRevealActiveFile() = runComposeUiTest {
        val fs = FileSystemService(
            BrokerApi("http://h", "t", HttpClient(MockEngine { respond("{}") })),
            send = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 0,
        )
        val view = TreeViewState("/w")
        val changes = mutableListOf<Boolean>()
        setContent(host { FileTreeHeader(view, fs, revealActive = true, onRevealActiveChange = { changes += it }) })
        onNodeWithTag("tree_menu").performClick()
        waitForIdle()
        onNodeWithText("Reveal active file").assertIsDisplayed()
        onNodeWithTag("tree_menu_reveal_active").performClick()
        waitForIdle()
        assertEquals(listOf(false), changes)
        onNodeWithText("Reveal active file").assertDoesNotExist()
    }

    @Test fun noMenuWithoutAToggleCallback() = runComposeUiTest {
        val view = TreeViewState("/w")
        setContent(host { FileTreeHeader(view, null) })
        onNodeWithTag("tree_menu").assertDoesNotExist()
    }

    @Test fun menuOffersNewFileAndFolderAtTheRoot() = runComposeUiTest {
        val view = TreeViewState("/w")
        val asked = mutableListOf<Boolean>()
        setContent(host { FileTreeHeader(view, null, onNewEntry = { asked += it }) })
        onNodeWithTag("tree_menu").performClick()
        waitForIdle()
        onNodeWithTag("tree_menu_reveal_active").assertDoesNotExist()
        onNodeWithTag("tree_menu_root_new_file").performClick()
        waitForIdle()
        onNodeWithTag("tree_menu").performClick()
        waitForIdle()
        onNodeWithTag("tree_menu_root_new_folder").performClick()
        waitForIdle()
        assertEquals(listOf(false, true), asked)
    }
}
