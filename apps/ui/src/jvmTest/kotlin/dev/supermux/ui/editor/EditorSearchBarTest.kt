package dev.supermux.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.FsSearchResult
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared search chrome: the field's empty-state placeholder and its edits, and the overlay's
 * two exits — tapping a result (which must report the result's path) and tapping the scrim. Run in
 * both modes because the haptic tick folded in from Android fires on the same two call sites, and a
 * missing no-op haptics provider on the Pointer path would blow up there rather than in the field.
 */
@OptIn(ExperimentalTestApi::class)
class EditorSearchBarTest {

    private fun result(path: String, ignored: Boolean = false) =
        FsSearchResult(path = path, name = path.substringAfterLast('/'), type = "file", ignored = ignored)

    @Test fun the_field_shows_its_placeholder_and_reports_edits_under_touch() = runComposeUiTest {
        var query = ""
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorSearchField(query = query, onQueryChange = { query = it })
                }
            }
        }
        onNodeWithText("Search files…").assertIsDisplayed()
        onNodeWithText("Search files…").performTextInput("Main")
        assertEquals("Main", query)
    }

    @Test fun tapping_a_result_reports_its_path_under_pointer() = runComposeUiTest {
        val picked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Pointer,
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorSearchOverlay(
                        results = listOf(result("src/Main.kt"), result("build/out.txt", ignored = true)),
                        onSelect = { picked += it },
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithContentDescription("src/Main.kt", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("build/out.txt", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithContentDescription("src/Main.kt", useUnmergedTree = true).performClick()
        assertEquals(listOf("src/Main.kt"), picked)
    }

    @Test fun tapping_a_result_reports_its_path_under_touch() = runComposeUiTest {
        val picked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    EditorSearchOverlay(
                        results = listOf(result("src/Main.kt")),
                        onSelect = { picked += it },
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithContentDescription("src/Main.kt", useUnmergedTree = true).performClick()
        assertEquals(listOf("src/Main.kt"), picked)
    }
}
