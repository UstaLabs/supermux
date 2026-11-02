package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.supermux.net.FsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `@` file mention logic behind the chat composer's menu: which word the caret is in, what the
 * menu asks the host for, and what a pick writes into the draft. Plain functions, so the contract is
 * pinned without Compose.
 */
class FileMentionsTest {

    // ── activeMentionToken ───────────────────────────────────────────────────────

    @Test fun noTokenWithoutAnAt() {
        assertNull(activeMentionToken("hello", 5))
    }

    @Test fun aBareAtIsAnEmptyQuery() {
        assertEquals(MentionToken(start = 0, end = 1, query = "", quoted = false), activeMentionToken("@", 1))
    }

    @Test fun anAtAfterWhitespaceStartsAToken() {
        assertEquals(MentionToken(4, 11, "src/fo", false), activeMentionToken("see @src/fo", 11))
        assertEquals(MentionToken(6, 8, "a", false), activeMentionToken("line1\n@a", 8))
    }

    @Test fun anAtInsideAWordNeverTriggers() {
        assertNull(activeMentionToken("mail me@example.com", 19))
    }

    @Test fun theTokenIsTheOneAtTheCaretNotAtTheEndOfTheDraft() {
        assertEquals(MentionToken(4, 7, "sr", false), activeMentionToken("see @sr and more", 7))
        assertNull(activeMentionToken("see @sr and more", 16))
    }

    @Test fun aCaretInsideAWordCoversTheWholeWord() {
        // Only what is left of the caret is the query; the pick replaces the word up to its end.
        assertEquals(MentionToken(0, 11, "src/", false), activeMentionToken("@src/foo.ts rest", 5))
    }

    @Test fun aSpaceAfterTheWordClosesIt() {
        assertNull(activeMentionToken("@src/a.ts ", 10))
    }

    @Test fun anOpenQuoteKeepsSpacesInTheQuery() {
        assertEquals(MentionToken(0, 12, "my notes/w", true), activeMentionToken("@\"my notes/w", 12))
    }

    @Test fun aClosedQuoteEndsTheToken() {
        assertNull(activeMentionToken("@\"my notes/w.txt\"", 17))
    }

    @Test fun aCaretOutsideTheTextIsNoToken() {
        assertNull(activeMentionToken("@a", 0))
        assertNull(activeMentionToken("@a", 9))
        assertNull(activeMentionToken("@a", -1))
    }

    // ── mentionLookup ────────────────────────────────────────────────────────────

    @Test fun anEmptyQueryListsTheWorkdir() {
        assertEquals(MentionLookup.Folder(""), mentionLookup(""))
    }

    @Test fun aTrailingSlashListsThatFolder() {
        assertEquals(MentionLookup.Folder("src"), mentionLookup("src/"))
        assertEquals(MentionLookup.Folder("src/ui"), mentionLookup("src/ui/"))
        assertEquals(MentionLookup.Folder("my notes"), mentionLookup("my notes/"))
    }

    @Test fun anythingElseIsAFuzzySearch() {
        assertEquals(MentionLookup.Search("comp"), mentionLookup("comp"))
        assertEquals(MentionLookup.Search("src/co"), mentionLookup("src/co"))
    }

    @Test fun pathsThatLeaveTheWorkdirAreNotLookedUp() {
        assertNull(mentionLookup("/etc/"))
        assertNull(mentionLookup("../other/"))
        assertNull(mentionLookup("src/../../x"))
    }

    // ── mentionText ──────────────────────────────────────────────────────────────

    @Test fun aFileIsAtItsRelativePath() {
        assertEquals("@src/a.ts", mentionText("src/a.ts", isDir = false))
    }

    @Test fun aFolderEndsInASlash() {
        assertEquals("@src/", mentionText("src", isDir = true))
        assertEquals("@src/", mentionText("src/", isDir = true))
    }

    @Test fun aPathWithWhitespaceIsQuoted() {
        assertEquals("@\"my notes/w.txt\"", mentionText("my notes/w.txt", isDir = false))
        assertEquals("@\"my notes/\"", mentionText("my notes", isDir = true))
    }

    @Test fun anOpenMentionLeavesTheQuoteOpen() {
        assertEquals("@\"my notes/", mentionText("my notes", isDir = true, open = true))
        assertEquals("@src/", mentionText("src", isDir = true, open = true))
    }

    // ── applyMention ─────────────────────────────────────────────────────────────

    @Test fun aPickReplacesTheTokenAndAddsASpace() {
        val token = activeMentionToken("see @sr", 7)!!
        assertEquals(MentionEdit("see @src/a.ts ", 14), applyMention("see @sr", token, "src/a.ts", isDir = false))
    }

    @Test fun aPickInTheMiddleReusesTheSpaceAfterIt() {
        val token = activeMentionToken("see @sr and", 7)!!
        assertEquals(MentionEdit("see @src/a.ts and", 14), applyMention("see @sr and", token, "src/a.ts", isDir = false))
    }

    @Test fun aPickReplacesTheWholeWordTheCaretIsIn() {
        val token = activeMentionToken("@src/foo.ts rest", 5)!!
        assertEquals(MentionEdit("@src/bar.ts rest", 12), applyMention("@src/foo.ts rest", token, "src/bar.ts", isDir = false))
    }

    @Test fun pickingAFolderLeavesTheWordOpenOnIt() {
        val token = activeMentionToken("@sr", 3)!!
        assertEquals(MentionEdit("@src/", 5), applyMention("@sr", token, "src", isDir = true))
    }

    @Test fun openingAFolderKeepsTheTokenOpenOnItsListing() {
        val token = activeMentionToken("see @sr", 7)!!
        val edit = applyMention("see @sr", token, "src", isDir = true, intoFolder = true)
        assertEquals(MentionEdit("see @src/", 9), edit)
        assertEquals(MentionLookup.Folder("src"), mentionLookup(activeMentionToken(edit.text, edit.caret)!!.query))
    }

    @Test fun openingAFolderWithASpaceKeepsTheQuoteOpenUntilAFileIsPicked() {
        val opened = applyMention("@my", activeMentionToken("@my", 3)!!, "my notes", isDir = true, intoFolder = true)
        assertEquals(MentionEdit("@\"my notes/", 11), opened)
        val token = activeMentionToken(opened.text, opened.caret)!!
        assertEquals(MentionLookup.Folder("my notes"), mentionLookup(token.query))
        assertEquals(
            MentionEdit("@\"my notes/w.txt\" ", 18),
            applyMention(opened.text, token, "my notes/w.txt", isDir = false),
        )
    }

    @Test fun openingAFileIsJustAPick() {
        val token = activeMentionToken("@a", 2)!!
        assertEquals(MentionEdit("@a.ts ", 6), applyMention("@a", token, "a.ts", isDir = false, intoFolder = true))
    }

    // ── mentionRows ──────────────────────────────────────────────────────────────

    private fun entry(name: String, type: String = "file", ignored: Boolean = false, target: String? = null) =
        FsEntry(name = name, type = type, ignored = ignored, target = target)

    @Test fun aListingPutsFoldersFirstThenFilesByName() {
        val rows = mentionRows(
            "/w",
            "",
            listOf(entry("b.kt"), entry("src", "dir"), entry("A.md"), entry("apps", "dir")),
        )
        assertEquals(listOf("apps", "src", "A.md", "b.kt"), rows.map { it.relativePath })
        assertEquals(listOf(true, true, false, false), rows.map { it.isDir })
    }

    @Test fun aListingHidesIgnoredEntriesAndDotGit() {
        val rows = mentionRows(
            "/w",
            "",
            listOf(entry(".git", "dir"), entry("node_modules", "dir", ignored = true), entry(".github", "dir"), entry("a.kt")),
        )
        assertEquals(listOf(".github", "a.kt"), rows.map { it.relativePath })
    }

    @Test fun aSubfolderListingIsRelativeToTheWorkdir() {
        val row = mentionRows("/w", "src/ui", listOf(entry("Chat.kt"))).single()
        assertEquals("src/ui/Chat.kt", row.relativePath)
        assertEquals("Chat.kt", row.name)
        assertEquals("src/ui", row.folder)
        assertEquals("/w/src/ui/Chat.kt", row.absolutePath)
    }

    @Test fun aSymlinkToAFolderIsAFolder() {
        val row = mentionRows("/w", "", listOf(entry("linked", "symlink", target = "dir"))).single()
        assertEquals(true, row.isDir)
    }

    // ── composerMenuKey ──────────────────────────────────────────────────────────

    @Test fun theMenuKeysMovePickCompleteAndClose() {
        assertEquals(ComposerMenuKey.Down, composerMenuKey(Key.DirectionDown, KeyEventType.KeyDown, shiftPressed = false))
        assertEquals(ComposerMenuKey.Up, composerMenuKey(Key.DirectionUp, KeyEventType.KeyDown, shiftPressed = false))
        assertEquals(ComposerMenuKey.Pick, composerMenuKey(Key.Enter, KeyEventType.KeyDown, shiftPressed = false))
        assertEquals(ComposerMenuKey.Pick, composerMenuKey(Key.NumPadEnter, KeyEventType.KeyDown, shiftPressed = false))
        assertEquals(ComposerMenuKey.Complete, composerMenuKey(Key.Tab, KeyEventType.KeyDown, shiftPressed = false))
        assertEquals(ComposerMenuKey.Dismiss, composerMenuKey(Key.Escape, KeyEventType.KeyDown, shiftPressed = false))
    }

    @Test fun shiftEnterStaysANewlineAndShiftTabIsNotAMenuKey() {
        assertNull(composerMenuKey(Key.Enter, KeyEventType.KeyDown, shiftPressed = true))
        assertNull(composerMenuKey(Key.Tab, KeyEventType.KeyDown, shiftPressed = true))
    }

    @Test fun onlyKeyDownCountsAndOtherKeysTypeAsUsual() {
        assertNull(composerMenuKey(Key.Enter, KeyEventType.KeyUp, shiftPressed = false))
        assertNull(composerMenuKey(Key.A, KeyEventType.KeyDown, shiftPressed = false))
        assertNull(composerMenuKey(Key.Spacebar, KeyEventType.KeyDown, shiftPressed = false))
    }
}
