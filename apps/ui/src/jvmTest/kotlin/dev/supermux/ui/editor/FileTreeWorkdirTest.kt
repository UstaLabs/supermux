package dev.supermux.ui.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.FsEntry
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Follow-up (b) from cluster B: `ExplorerState` is `remember(workspaceId)`-ed by both hosts, so a
 * workdir change under the same workspace used to leave the PREVIOUS checkout's tree on screen.
 * [FileTree] now resets and re-lists — but must NOT double the very first listing, which is what
 * the `seenWorkdir` guard buys.
 */
@OptIn(ExperimentalTestApi::class)
class FileTreeWorkdirTest {

    @Test fun the_first_composition_lists_the_root_exactly_once() = runComposeUiTest {
        val listed = mutableListOf<String>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FileTree(
                    fsList = { p -> listed += p; Result.success(listOf(FsEntry("a.kt", "file"))) },
                    explorer = ExplorerState(),
                    workdir = "/w/one",
                    onOpenFile = {},
                )
            }
        }
        waitForIdle()
        assertEquals(listOf("."), listed) // one root listing, not two
    }

    // The marker lives on ExplorerState, not on the composable: Android composes the tree
    // conditionally (`treeVisible`, and again inside the drawer), so a marker that died with the
    // composable would forget the old workdir every time the tree was closed — and a workdir change
    // made WHILE it was hidden would then look like a first sight and never reset.
    @Test fun a_workdir_change_while_the_tree_is_hidden_still_resets_it_on_reopen() = runComposeUiTest {
        val listed = mutableListOf<String>()
        val explorer = ExplorerState()
        val workdir = mutableStateOf("/w/one")
        val visible = mutableStateOf(true)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                if (visible.value) {
                    FileTree(
                        fsList = { p -> listed += p; Result.success(listOf(FsEntry("a.kt", "file"))) },
                        explorer = explorer,
                        workdir = workdir.value,
                        onOpenFile = {},
                    )
                }
            }
        }
        waitForIdle()
        explorer.expandedPaths = setOf("src")

        visible.value = false            // tree closed…
        waitForIdle()
        workdir.value = "/w/two"         // …workdir changes while it is not composed…
        waitForIdle()
        visible.value = true             // …and it comes back
        waitForIdle()

        assertEquals(listOf(".", "."), listed) // the new workdir's root WAS listed again
        assertTrue(explorer.expandedPaths.isEmpty()) // the old checkout's expansion is gone
    }

    @Test fun changing_the_workdir_resets_the_tree_and_re_lists_the_root() = runComposeUiTest {
        val listed = mutableListOf<String>()
        val explorer = ExplorerState()
        val workdir = mutableStateOf("/w/one")
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                FileTree(
                    fsList = { p -> listed += p; Result.success(listOf(FsEntry("a.kt", "file"))) },
                    explorer = explorer,
                    workdir = workdir.value,
                    onOpenFile = {},
                )
            }
        }
        waitForIdle()
        explorer.expandedPaths = setOf("src") // stale state from the old checkout
        explorer.treeLoadError = mapOf("src" to "boom")

        workdir.value = "/w/two"
        waitForIdle()

        assertEquals(listOf(".", "."), listed) // the root was listed again for the new workdir
        assertTrue(explorer.expandedPaths.isEmpty())
        assertTrue(explorer.treeLoadError.isEmpty())
        assertTrue(explorer.treeRootLoaded)
    }
}
