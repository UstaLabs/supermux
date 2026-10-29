package dev.supermux.state

import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.session.groupSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class HostReducerAgentsTest {
    private val agent = SessionInfo(id = "a1", name = "Dockie", workdir = "/home/u/.mux/workspace/Dockie", agent = "claude", role = "personal_assistant")

    @Test fun a_new_agent_from_session_added_lands_in_the_pinned_group() {
        val state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(agent))
        val groups = groupSessions(state.sessions, home = "/home/u")
        assertEquals(PA_GROUP_KEY, groups.first().workdir)
        assertEquals(listOf("a1"), groups.first().sessions.map { it.id })
    }

    @Test fun an_update_without_a_role_keeps_the_known_role() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(agent))
        state = reduceHostFrame(state, ServerFrame.SessionAdded(agent.copy(role = null, name = "Dockie 2")))
        assertEquals("personal_assistant", state.sessions.single().role)
    }

    @Test fun the_pinned_group_is_labelled_agents() {
        val state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(agent))
        assertEquals("Agents", groupSessions(state.sessions, home = "/home/u").first().label)
    }
}
