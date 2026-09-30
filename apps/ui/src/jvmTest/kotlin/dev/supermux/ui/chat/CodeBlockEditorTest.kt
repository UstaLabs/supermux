package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fenced code block in a message is the native editor, read-only: the text is the editor's, the
 * block is as tall as its lines, a streamed chunk reaches the same view, the copy button stays, and
 * a wheel over the block scrolls the CHAT (the block never scrolls vertically itself).
 */
@OptIn(ExperimentalTestApi::class)
class CodeBlockEditorTest {

    private val code = (1..20).joinToString("\n") { "val line$it = $it" }

    @Test fun fencedBlock_isTheEditor_withTheCopyButton() = runComposeUiTest {
        setPlatformContent { MarkdownBody("before\n\n```kotlin\n$code\n```\n\nafter") }
        onNodeWithTag("chat_code_editor").assertExists()
        // The surface's text node (the hidden input field holds only a window of it), named by the fence language.
        onNodeWithContentDescription("kotlin code").assert(hasText("val line20 = 20", substring = true))
        onNodeWithContentDescription("Copy").assertExists()
    }

    @Test fun block_isAsTallAsItsLines() = runComposeUiTest {
        setPlatformContent { MarkdownBody("```\n$code\n```") }
        // 20 lines of 12sp × 1.5 at density 1: 18 px each.
        onNodeWithTag("chat_code_editor").assertHeightIsEqualTo((20 * 18).dp)
    }

    @Test fun streamedCode_reachesTheSameEditor() = runComposeUiTest {
        var text by mutableStateOf("```ts\nconst a = 1\n```")
        setPlatformContent { MarkdownBody(text) }
        onNodeWithContentDescription("typescript code").assert(hasText("const a = 1", substring = true))
        text = "```ts\nconst a = 1\nconst b = 2\n```"
        waitForIdle()
        onNodeWithContentDescription("typescript code").assert(hasText("const a = 1\nconst b = 2"))
        onNodeWithTag("chat_code_editor").assertHeightIsEqualTo((2 * 18).dp)
    }

    @Test fun wheelOverTheBlock_scrollsTheChat() = runComposeUiTest {
        val chat = androidx.compose.foundation.ScrollState(0)
        setPlatformContent {
            Column(Modifier.height(300.dp).width(400.dp).verticalScroll(chat)) {
                MarkdownBody("```kotlin\n$code\n```")
                Spacer(Modifier.height(1000.dp))
            }
        }
        onNodeWithTag("chat_code_editor").performMouseInput { enter(center); scroll(3f) }
        waitForIdle()
        assertTrue(chat.value > 0, "a wheel over the code block must scroll the chat (was ${chat.value})")
    }
}
