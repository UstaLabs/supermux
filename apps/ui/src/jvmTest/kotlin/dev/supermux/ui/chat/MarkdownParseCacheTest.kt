package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.browser.LocalOpenLink
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A markdown body parses its text once: a recomposition with the same text (a new handler, say) parses nothing. */
@OptIn(ExperimentalTestApi::class)
class MarkdownParseCacheTest {
    private val text = "**Bold** with `code`, a path src/app/Main.kt and a [link](https://example.com/).\n\n- one\n- two"

    @Test fun aRecompositionWithTheSameTextDoesNotParseAgain() = runComposeUiTest {
        val counts = mutableMapOf<String, Int>()
        val tick = mutableIntStateOf(0)
        val body = mutableStateOf(text)
        setPlatformContent {
            CompositionLocalProvider(LocalChatCompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                val t = tick.intValue
                // A new handler each time: MarkdownBody recomposes.
                MarkdownBody(body.value, onOpenFile = { t.let { } }, linkify = true)
            }
        }
        waitForIdle()
        val parsed = counts["parse"] ?: 0
        val composed = counts["markdown"] ?: 0
        assertTrue(parsed > 0, "the first composition parses: $counts")
        repeat(3) {
            tick.intValue++
            waitForIdle()
        }
        assertEquals(composed + 3, counts["markdown"], "the body recomposed with each new handler: $counts")
        assertEquals(parsed, counts["parse"], "and parsed nothing again: $counts")

        body.value = "$text\n\nMore."
        waitForIdle()
        assertTrue((counts["parse"] ?: 0) > parsed, "a new text is parsed: $counts")
    }

    @Test fun aLinkCallsTheHandlerOfTheLatestComposition() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val handler = mutableStateOf<(String) -> Unit>({ opened += "first:$it" })
        setPlatformContent(platform = FakePlatform()) {
            CompositionLocalProvider(LocalOpenLink provides handler.value) { MarkdownBody("[Vite](https://vitejs.dev/)") }
        }
        handler.value = { opened += "second:$it" }
        waitForIdle()
        onNodeWithText("Vite").performMouseInput { click(Offset(8f, height / 2f)) }
        waitForIdle()
        assertEquals(listOf("second:https://vitejs.dev/"), opened)
    }
}
