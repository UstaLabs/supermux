package dev.supermux.state

import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SessionPrDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** #152 B5: where a session's work comes from (its pull request, where pushes go) survives a later session_added. */
class HostReducerSessionSourceTest {
    private val pr = SessionPrDto("github", 123, "https://github.com/acme/storefront/pull/123")
    private val session = SessionInfo(id = "s1", name = "fix", workdir = "/w", agent = "claude", source = "pr", pr = pr, push_remote = "origin")

    @Test fun a_session_added_without_the_source_keeps_it() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(session))
        state = reduceHostFrame(state, ServerFrame.SessionAdded(SessionInfo(id = "s1", name = "renamed", workdir = "/w", agent = "claude")))
        val s = state.sessions.single()
        assertEquals("renamed", s.name)
        assertEquals("pr", s.source)
        assertEquals(pr, s.pr)
        assertEquals("origin", s.push_remote)
    }
}
