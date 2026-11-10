package dev.supermux.ui.session

import dev.supermux.chat.formatChatTime
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.LimitWaitDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #92: an Agent waiting for a usage-limit reset says so in its sidebar row, like a session row. */
class AgentRowLimitWaitTest {
    private val now = parseChatTs("2026-11-10T03:00:00.000Z")!!
    private val wait = LimitWaitDto(resumeAt = "2026-11-10T03:08:23.000Z", resetsAt = "2026-11-10T03:03:40.000Z", account = "a", accountLabel = "3@supermux.dev")
    private val anya = AgentEntry(id = "w", name = "Anya", working = false, unread = false, active = false, online = true, muted = false, preview = null)

    @Test fun a_waiting_agent_says_when_it_resumes() {
        val hhmm = formatChatTime(parseChatTs(wait.resumeAt)!!, now)
        assertEquals("Waiting · resumes $hhmm", agentRowStatus(anya.copy(limitWait = wait), now))
        assertEquals("Waiting · resumes $hhmm", agentRowStatus(anya.copy(limitWait = wait, online = false), now))
    }

    @Test fun working_wins_and_the_rest_is_as_before() {
        assertEquals("working…", agentRowStatus(anya.copy(limitWait = wait, working = true), now))
        assertEquals("asleep", agentRowStatus(anya.copy(online = false), now))
        assertNull(agentRowStatus(anya, now))
    }
}
