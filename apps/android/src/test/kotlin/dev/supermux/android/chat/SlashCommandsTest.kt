package dev.supermux.android.chat

import dev.supermux.proto.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM unit tests for the slash-command matching logic shared by the chat composer and the
 * New Session launcher (mirrors iOS ComposerModel.slashMatches / replaceSlashToken). No Compose or
 * Android framework needed — these are plain functions, so this pins the contract both composers
 * rely on (the launcher used to ship without any of it because the logic was inline in ChatPanel).
 */
class SlashCommandsTest {
    private fun cmd(name: String, family: String = "custom", insertText: String? = null, sigil: String = "/") =
        SlashCommand(id = "$family/$name", family = family, name = name, sigil = sigil, insertText = insertText)

    private val commands = listOf(
        cmd("review", family = "project"),
        cmd("refactor", family = "project"),
        cmd("commit", family = "git"),
        cmd("test", family = "git"),
    )

    // ── activeSlashQuery ─────────────────────────────────────────────────────────

    @Test fun query_null_whenNoSlashToken() {
        assertNull(activeSlashQuery(""))
        assertNull(activeSlashQuery("just some text"))
    }

    @Test fun query_ignoresMidWordSlash_soUrlsDontTrigger() {
        assertNull(activeSlashQuery("see https://example.com/path"))
    }

    @Test fun query_bareSlash_isEmptyString_notNull() {
        assertEquals("", activeSlashQuery("/"))
        assertEquals("", activeSlashQuery("do it /"))
    }

    @Test fun query_lowercasesAndStripsLeadingSlash() {
        assertEquals("rev", activeSlashQuery("/Rev"))
        assertEquals("commit", activeSlashQuery("please /COMMIT"))
    }

    @Test fun query_onlyMatchesTokenAtEndOfDraft() {
        assertNull(activeSlashQuery("/review then more"))
    }

    // ── slashCommandMatches ──────────────────────────────────────────────────────

    @Test fun matches_empty_whenNoActiveToken() {
        assertEquals(emptyList(), slashCommandMatches("hello", commands))
    }

    @Test fun matches_bareSlash_returnsAll() {
        assertEquals(commands, slashCommandMatches("/", commands))
    }

    @Test fun matches_byNamePrefixSubstring() {
        val m = slashCommandMatches("/re", commands).map { it.name }
        assertEquals(listOf("review", "refactor"), m)
    }

    @Test fun matches_byFamily() {
        val m = slashCommandMatches("/git", commands).map { it.name }
        assertEquals(listOf("commit", "test"), m)
    }

    @Test fun matches_cappedAtEight() {
        val many = (1..20).map { cmd("cmd$it") }
        assertEquals(8, slashCommandMatches("/cmd", many).size)
    }

    // ── replaceSlashToken ────────────────────────────────────────────────────────

    @Test fun replace_preservesLeadingWhitespace() {
        assertEquals("do it /review ", replaceSlashToken("do it /re", "/review "))
    }

    @Test fun replace_atStartOfDraft() {
        assertEquals("/review ", replaceSlashToken("/re", "/review "))
    }

    @Test fun replace_withEmpty_clearsTheToken() {
        assertEquals("do it ", replaceSlashToken("do it /commit", ""))
    }

    @Test fun replace_noToken_becomesInsert() {
        assertEquals("/review ", replaceSlashToken("no token here", "/review "))
    }

    // ── slashInsertText ──────────────────────────────────────────────────────────

    @Test fun insertText_defaultsToSigilNameSpace() {
        assertEquals("/review ", slashInsertText(cmd("review")))
    }

    @Test fun insertText_usesExplicitInsertText_whenPresent() {
        assertEquals("/deploy --prod ", slashInsertText(cmd("deploy", insertText = "/deploy --prod ")))
    }

    @Test fun insertText_blankInsertText_fallsBackToName() {
        assertEquals("/review ", slashInsertText(cmd("review", insertText = "")))
    }

    // ── end-to-end: type "/re", pick first match, token is replaced ───────────────

    @Test fun endToEnd_pickFirstMatch_replacesTokenInDraft() {
        val draft = "fix the bug /re"
        val matches = slashCommandMatches(draft, commands)
        assertTrue(matches.isNotEmpty())
        val applied = replaceSlashToken(draft, slashInsertText(matches.first()))
        assertEquals("fix the bug /review ", applied)
    }
}
