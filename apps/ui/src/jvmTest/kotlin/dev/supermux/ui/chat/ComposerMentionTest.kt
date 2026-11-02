package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import dev.supermux.ui.files.GoToEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composer's `@` menu, wired: a typed `@` asks the host's [ComposerActions.mentionFiles] seam,
 * the rows sit where the `/` menu sits, and the menu keys (↑ ↓ Enter Tab Esc) drive it.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerMentionTest {

    private fun row(rel: String, dir: Boolean = false) = GoToEntry(
        absolutePath = "/w/$rel",
        relativePath = rel,
        name = rel.substringAfterLast('/'),
        folder = rel.substringBeforeLast('/', ""),
        isDir = dir,
    )

    /** A tiny project: `docs/`, `src/` and README.md at the top, two files in src. */
    private class Project {
        val asked = mutableListOf<MentionLookup>()
        fun files(lookup: MentionLookup, row: (String, Boolean) -> GoToEntry): List<GoToEntry> {
            synchronized(asked) { asked += lookup }
            return when (lookup) {
                MentionLookup.Folder("") -> listOf(row("docs", true), row("src", true), row("README.md", false))
                MentionLookup.Folder("src") -> listOf(row("src/Composer.kt", false), row("src/Main.kt", false))
                is MentionLookup.Search ->
                    listOf(row("src/Composer.kt", false), row("src/Main.kt", false))
                        .filter { lookup.query.lowercase() in it.relativePath.lowercase() }
                else -> emptyList()
            }
        }
    }

    private class Harness(val project: Project) {
        var draft by mutableStateOf("")
        val sent = mutableListOf<String>()
    }

    private fun ComposeUiTest.composer(): Harness {
        val h = Harness(Project())
        val actions = ComposerActions(mentionFiles = { h.project.files(it, ::row) })
        setPlatformContent {
            Composer(
                draft = h.draft,
                onDraftChange = { h.draft = it },
                sending = false,
                agentWorking = false,
                onSend = { text, _ -> h.sent += text },
                onInterrupt = {},
                actions = actions,
            )
        }
        return h
    }

    private fun ComposeUiTest.type(text: String) = onNodeWithTag("composer-input").performTextInput(text)

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithTag("composer-input").performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.awaitRow(rel: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("chat_mention_item_$rel").fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.assertNoMenu() {
        waitForIdle()
        onNodeWithTag("chat_mention_menu").assertDoesNotExist()
    }

    @Test fun aBareAtListsTheTopOfTheProject() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("README.md")
        onNodeWithTag("chat_mention_item_docs").assertIsDisplayed()
        onNodeWithTag("chat_mention_item_src").assertIsDisplayed()
        assertEquals(MentionLookup.Folder(""), h.project.asked.first())
    }

    @Test fun enterPicksTheHighlightedFileInsteadOfSending() = runComposeUiTest {
        val h = composer()
        type("fix @comp")
        awaitRow("src/Composer.kt")
        press(Key.Enter)
        assertEquals("fix @src/Composer.kt ", h.draft)
        assertEquals(emptyList(), h.sent)
        assertNoMenu()
    }

    @Test fun arrowsMoveTheHighlight() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("README.md")
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals("@README.md ", h.draft)
    }

    @Test fun tabOpensAFolderAndListsIt() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("src")
        press(Key.DirectionDown)
        press(Key.Tab)
        assertEquals("@src/", h.draft)
        awaitRow("src/Main.kt")
        assertTrue(MentionLookup.Folder("src") in h.project.asked)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals("@src/Main.kt ", h.draft)
    }

    @Test fun enterOnAFolderMentionsIt() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("src")
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals("@src/ ", h.draft)
        assertNoMenu()
    }

    @Test fun clickingARowPicksIt() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("README.md")
        onNodeWithTag("chat_mention_item_README.md").performClick()
        waitForIdle()
        assertEquals("@README.md ", h.draft)
    }

    @Test fun escClosesTheMenuForThatWord() = runComposeUiTest {
        val h = composer()
        type("@")
        awaitRow("README.md")
        press(Key.Escape)
        assertNoMenu()
        type("src")
        assertNoMenu()
        // A new word opens it again.
        type(" @")
        awaitRow("README.md")
        assertEquals("@src @", h.draft)
    }

    @Test fun anEmailNeverOpensIt() = runComposeUiTest {
        val h = composer()
        type("mail me@example.com")
        assertNoMenu()
        assertEquals(emptyList(), h.project.asked)
        press(Key.Enter)
        assertEquals(listOf("mail me@example.com"), h.sent)
    }

    @Test fun aMentionGoesWhereTheCaretIs() = runComposeUiTest {
        val h = composer()
        type("see  please")
        onNodeWithTag("composer-input").performTextInputSelection(TextRange(4))
        type("@comp")
        assertEquals("see @comp please", h.draft)
        awaitRow("src/Composer.kt")
        press(Key.Enter)
        assertEquals("see @src/Composer.kt please", h.draft)
        // The caret landed after the existing space, so typing continues the sentence.
        type("now")
        assertEquals("see @src/Composer.kt nowplease", h.draft)
    }
}
