package dev.supermux.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared tab strip is Android's chip geometry (desktop's flush square variant had no caller and
 * was dropped in cluster B/task B3), so the same rows render in both modes; what is pinned here is
 * that every open document and the pending `loadingPath` get a tagged chip, that the loading chip
 * has no close glyph, and that select/close report the document's path.
 */
@OptIn(ExperimentalTestApi::class)
class EditorTabsTest {

    private fun doc(path: String) = Document(path = path, content = "")

    @Test fun every_tab_gets_a_chip_and_a_close_glyph_under_touch() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorTabs(
                        tabs = listOf(doc("src/Main.kt"), doc("README.md")),
                        activeTabPath = "src/Main.kt",
                        isDirty = { it == "README.md" },
                        onSelect = {},
                        onClose = {},
                    )
                }
            }
        }
        onNodeWithTag(editorTabTag("src/Main.kt"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(editorTabTag("README.md"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(editorTabCloseTag("README.md"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun select_and_close_report_the_documents_path_under_pointer() = runComposeUiTest {
        val selected = mutableListOf<String>()
        val closed = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Pointer,
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorTabs(
                        tabs = listOf(doc("src/Main.kt"), doc("README.md")),
                        activeTabPath = "src/Main.kt",
                        isDirty = { false },
                        onSelect = { selected += it },
                        onClose = { closed += it },
                    )
                }
            }
        }
        onNodeWithTag(editorTabTag("README.md"), useUnmergedTree = true).performClick()
        assertEquals(listOf("README.md"), selected)

        onNodeWithTag(editorTabCloseTag("src/Main.kt"), useUnmergedTree = true).performClick()
        assertEquals(listOf("src/Main.kt"), closed)
    }

    @Test fun the_pending_loading_chip_has_its_own_tag_and_no_close_glyph() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorTabs(
                        tabs = emptyList(),
                        activeTabPath = null,
                        loadingPath = "src/Slow.kt",
                        isDirty = { false },
                        onSelect = {},
                        onClose = {},
                    )
                }
            }
        }
        onNodeWithTag(editorTabLoadingTag("src/Slow.kt"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(editorTabCloseTag("src/Slow.kt"), useUnmergedTree = true).assertDoesNotExist()
        // Distinct from the open-tab tag, so a path that is BOTH open and loading does not put one
        // tag on two nodes (which would make every onNodeWithTag on it ambiguous).
        onNodeWithTag(editorTabTag("src/Slow.kt"), useUnmergedTree = true).assertDoesNotExist()
    }
}
