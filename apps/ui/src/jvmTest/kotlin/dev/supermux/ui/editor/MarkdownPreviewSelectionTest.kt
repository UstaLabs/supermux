package dev.supermux.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.tripleClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text in a file's markdown preview selects and copies the way a chat reply's does: drag, double-
 * click a word, triple-click a paragraph, the copy shortcut, right-click → Copy, a long press on a
 * touch screen. The preview drew the chat's renderer without the chat's selection container, so
 * none of it selected. Links, file links and the wheel must keep working inside it.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownPreviewSelectionTest {

    private val first = "First paragraph of the preview."

    private val markdown = """
        # Notes

        $first

        See [the docs](https://example.com/docs) or `src/a.kt`.
    """.trimIndent()

    /** Records what a copy writes, instead of the system clipboard (there is none headless). */
    private class FakeClipboard : Clipboard {
        var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }

        @OptIn(ExperimentalComposeUiApi::class)
        @Suppress("DEPRECATION")
        val text: String?
            get() = (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    private val platform = FakePlatform()
    private val clipboard = FakeClipboard()
    private val openedFiles = mutableListOf<FilePathRef>()

    private fun ComposeUiTest.preview(text: String = markdown) {
        setContent {
            CompositionLocalProvider(
                LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
                LocalPlatform provides platform,
                LocalClipboard provides clipboard,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    FilePane(
                        path = "notes.md",
                        documents = DocumentStore(
                            fsRead = { Result.success(text) },
                            fsWrite = { _, _ -> true },
                            scope = CoroutineScope(Dispatchers.Unconfined),
                        ),
                        previewMode = true,
                        onOpenFile = { openedFiles += it },
                    )
                }
            }
        }
        waitForIdle()
    }

    /** A text node of the PREVIEW (the covered editor underneath holds the same words). */
    private fun ComposeUiTest.previewText(substring: String) =
        onNode(hasText(substring, substring = true) and hasAnyAncestor(hasTestTag("editor_preview")))

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun SemanticsNodeInteraction.pointAt(offset: Int): Offset = layout().getBoundingBox(offset).center

    private val copyChord = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft

    private fun SemanticsNodeInteraction.copyShortcut() = performKeyInput { withKeyDown(copyChord) { pressKey(Key.C) } }

    @Test fun dragging_across_preview_text_selects_it_and_the_copy_shortcut_copies_it() = runComposeUiTest {
        preview()
        val node = previewText(first)
        val layout = node.layout()
        val start = layout.getBoundingBox(0).centerLeft + Offset(1f, 0f)
        val end = layout.getBoundingBox(first.length - 1).centerRight - Offset(1f, 0f)

        node.performMouseInput { dragAndDrop(start, end) }
        node.copyShortcut()
        waitForIdle()

        assertEquals(first, clipboard.text)
    }

    @Test fun double_click_selects_a_word_and_triple_click_the_paragraph() = runComposeUiTest {
        preview()
        val node = previewText(first)
        val at = node.pointAt("First para".length)

        node.performMouseInput { doubleClick(at) }
        node.copyShortcut()
        waitForIdle()
        assertEquals("paragraph", clipboard.text)

        node.performMouseInput { tripleClick(at) }
        node.copyShortcut()
        waitForIdle()
        assertEquals(first, clipboard.text)
    }

    @Test fun right_click_on_a_selection_offers_copy() = runComposeUiTest {
        preview()
        val node = previewText(first)
        val at = node.pointAt("First para".length)

        node.performMouseInput { doubleClick(at) }
        node.performMouseInput { rightClick(at) }
        waitForIdle()
        onNodeWithText("Copy").performClick()
        waitForIdle()

        assertEquals("paragraph", clipboard.text)
    }

    @Test fun a_long_press_selects_the_word_under_the_finger() = runComposeUiTest {
        preview()
        val node = previewText(first)

        node.performTouchInput { longClick(node.pointAt("First para".length)) }
        waitForIdle()
        // A phone copies from the selection's toolbar; a tablet's keyboard takes the same shortcut.
        node.copyShortcut()
        waitForIdle()

        assertEquals("paragraph", clipboard.text)
    }

    @Test fun links_in_the_preview_still_open_on_click() = runComposeUiTest {
        preview()
        val node = previewText("See the docs")

        node.performMouseInput { click(node.pointAt("See the d".length)) }
        waitForIdle()
        assertEquals(listOf("https://example.com/docs"), platform.openedUrls)

        node.performMouseInput { click(node.pointAt("See the docs or src/a".length)) }
        waitForIdle()
        assertEquals(listOf("src/a.kt"), openedFiles.map { it.path })
    }

    @Test fun the_wheel_still_scrolls_the_preview() = runComposeUiTest {
        preview("# Top\n\n" + (1..120).joinToString("\n\n") { "Paragraph $it of a long file." })
        val top = previewText("Top")
        val before = top.fetchSemanticsNode().positionInRoot.y

        onNodeWithTag("editor_preview").performMouseInput {
            moveTo(center)
            scroll(10f)
        }
        waitForIdle()

        assertTrue(top.fetchSemanticsNode().positionInRoot.y < before, "the preview did not scroll")
    }
}
