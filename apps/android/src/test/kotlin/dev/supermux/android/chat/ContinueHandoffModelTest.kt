package dev.supermux.android.chat

import dev.supermux.session.HandoffPrefill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueHandoffModelTest {

    private val handoff = ContinueHandoff(
        message = "  pick up here  ",
        agent = "codex",
        model = "gpt-5",
        reasoningLevel = "high",
    )

    @Test
    fun continueBodyJoinsSourceWorkspace() {
        val body = continueSpawnRequest(
            sourceWorkdir = "/repo",
            sourceSessionId = "s-src",
            sourceName = "fix auth",
            sourceAgent = "claude",
            workspaceId = "w-1",
            handoff = handoff,
        )
        assertEquals("/repo", body.workdir)
        assertEquals("w-1", body.workspaceId)
        assertEquals("s-src", body.inheritFrom)
        assertEquals("pick up here", body.firstMessage)
        assertEquals("codex", body.agent)
        assertEquals("gpt-5", body.model)
        assertEquals("high", body.reasoningLevel)
        assertEquals("fix auth", body.name)
        assertTrue(brokerDeliversFirstMessage(body))
    }

    @Test
    fun continueWithoutWorkspaceStillInherits() {
        val body = continueSpawnRequest(
            sourceWorkdir = "/old",
            sourceSessionId = "s-old",
            sourceName = null,
            sourceAgent = "mystery",
            workspaceId = null,
            handoff = ContinueHandoff(
                message = "go",
                agent = "",
                model = null,
                reasoningLevel = null,
            ),
        )
        assertNull(body.workspaceId)
        assertEquals("s-old", body.inheritFrom)
        assertEquals("go", body.firstMessage)
        assertEquals(HandoffPrefill.defaultAgent("mystery"), body.agent)
        assertTrue(brokerDeliversFirstMessage(body))
    }

    @Test
    fun newChatHereJoinsWorkspaceWithoutInherit() {
        val body = newChatHereRequest(
            workspaceId = "w-2",
            workdir = "/ws",
            agent = "claude",
        )
        assertEquals("w-2", body.workspaceId)
        assertEquals("/ws", body.workdir)
        assertNull(body.inheritFrom)
        assertNull(body.firstMessage)
        assertEquals("claude", body.agent)
    }

    @Test
    fun continuePathBrokerDeliversFirstMessage() {
        val body = continueSpawnRequest(
            sourceWorkdir = "/r",
            sourceSessionId = "s1",
            sourceName = "n",
            sourceAgent = "claude",
            workspaceId = "w",
            handoff = ContinueHandoff("handoff text", "claude", null, null),
        )
        assertTrue(!body.firstMessage.isNullOrBlank())
        assertTrue(
            brokerDeliversFirstMessage(body),
            "broker delivers firstMessage; AppViewModel must not setPendingFirst / ClientFrame.Send",
        )
    }

    @Test
    fun overflowContinueTestIdMatchesContract() {
        assertEquals("overflow_continue", ChatOverflowTestIds.CONTINUE)
    }
}
