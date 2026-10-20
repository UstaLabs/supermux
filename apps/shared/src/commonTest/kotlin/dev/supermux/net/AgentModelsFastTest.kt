package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentModelsFastTest {
    private val claude = AgentModels(
        kind = "claude",
        models = listOf(ModelInfo("claude-opus-5-5", "Opus", supportsFast = true), ModelInfo("claude-sonnet-5-5", "Sonnet")),
        fast = true,
    )

    @Test fun a_model_answers_from_its_own_flag() {
        assertTrue(claude.fastFor("claude-opus-5-5"))
        assertFalse(claude.fastFor("claude-sonnet-5-5"))
        assertFalse(claude.fastFor("not-in-the-catalog"))
    }

    @Test fun no_model_answers_from_the_agent_default() {
        assertTrue(claude.fastFor(null))
        assertFalse(claude.copy(fast = false).fastFor(null))
    }
}
