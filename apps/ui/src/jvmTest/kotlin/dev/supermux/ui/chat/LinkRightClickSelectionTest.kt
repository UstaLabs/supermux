package dev.supermux.ui.chat

import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Right-clicking a link in chat markdown selects the WHOLE link — the stock behavior selected only
 * the word under the pointer, so right-click → Copy on a URL copied `github`.
 */
@OptIn(ExperimentalTestApi::class)
class LinkRightClickSelectionTest {

    private val url = "https://github.com/foo/bar"

    private fun SemanticsNodeInteraction.pointAt(offset: Int): Offset {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().getBoundingBox(offset).center
    }

    @Test fun rightClick_midLink_selectsTheWholeLink_evenAfterAnEarlierBlock() = runComposeUiTest {
        val state = SelectionState()
        setPlatformContent {
            LinkSelectionContainer(state = state) {
                MarkdownBody(text = "first paragraph\n\nsee $url now", linkify = true)
            }
        }
        val node = onNodeWithText("see https", substring = true)
        // "github" — the word the stock behavior would have selected on its own.
        val at = node.pointAt("see https://git".length)
        node.performMouseInput { rightClick(at) }
        waitForIdle()

        assertEquals(url, state.selectedTexts.joinToString("") { it.text })
    }

    @Test fun rightClick_linkOpeningABlock_selectsExactlyTheLink() = runComposeUiTest {
        val state = SelectionState()
        setPlatformContent {
            LinkSelectionContainer(state = state) {
                MarkdownBody(text = "first paragraph\n\n$url is the repo", linkify = true)
            }
        }
        val node = onNodeWithText("$url is", substring = true)
        node.performMouseInput { rightClick(node.pointAt("https://github.com/f".length)) }
        waitForIdle()

        assertEquals(url, state.selectedTexts.joinToString("") { it.text })
    }
}
