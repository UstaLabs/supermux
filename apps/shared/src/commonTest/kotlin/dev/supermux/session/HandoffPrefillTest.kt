package dev.supermux.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Continue in a new conversation" hands the next agent a first message it must be able to act on
 * alone. These pin the load-bearing parts of that text: without the source id and the explicit
 * `read_session` instruction the new agent starts blind, and without the "workspace files are
 * authoritative" line it trusts a stale transcript over the repo it can actually read.
 */
class HandoffPrefillTest {

    @Test fun buildCarriesTheSessionNameIdAndReadSessionInstruction() {
        val text = HandoffPrefill.build(name = "Fix auth race", id = "sess-abc")
        assertTrue(text.contains("Continue work from the prior supermux session"), text)
        assertTrue(text.contains("Session: Fix auth race"), text)
        assertTrue(text.contains("Source session id: sess-abc"), text)
        assertTrue(text.contains("read_session with session_id \"sess-abc\""), text)
        assertTrue(text.contains("workspace files as authoritative"), text)
    }

    /** An unnamed session is common (drafts, spawned children); the prefill still has to read. */
    @Test fun buildFallsBackToAPlaceholderWhenTheNameIsBlank() {
        val text = HandoffPrefill.build(name = "  ", id = "x")
        assertTrue(text.contains("Session: previous session"), text)
        assertTrue(text.contains("Source session id: x"), text)
    }

    /**
     * Continuing keeps you on the agent you were using — the handoff is about the conversation, not
     * about switching tools. The broker's agent field is not case-normalised, so the match must not
     * be either.
     */
    @Test fun defaultAgentPrefersAKnownSourceAgentCaseInsensitively() {
        assertEquals("codex", HandoffPrefill.defaultAgent("codex"))
        assertEquals("grok", HandoffPrefill.defaultAgent("GROK"))
        assertEquals("cursor", HandoffPrefill.defaultAgent("  Cursor "))
    }

    /** An agent we cannot continue into (or none at all) must land on claude, never on garbage. */
    @Test fun defaultAgentFallsBackToClaudeForUnknownOrMissingAgents() {
        assertEquals("claude", HandoffPrefill.defaultAgent("unknown"))
        assertEquals("claude", HandoffPrefill.defaultAgent(null))
        assertEquals("claude", HandoffPrefill.defaultAgent(""))
    }
}
