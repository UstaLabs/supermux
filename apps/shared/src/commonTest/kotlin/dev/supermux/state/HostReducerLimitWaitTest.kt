package dev.supermux.state

import dev.supermux.proto.LimitWaitDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #92: a session's wait for a usage-limit reset arrives on the snapshot and in `limit_wait` frames. */
class HostReducerLimitWaitTest {
    private val wait = LimitWaitDto(resumeAt = "2026-11-08T18:13:00.000Z", resetsAt = "2026-11-08T18:10:00.000Z", account = "work", accountLabel = "Work Max")
    private val session = SessionInfo(id = "s1", name = "fix", workdir = "/w", agent = "claude")

    @Test fun a_limit_wait_frame_sets_the_wait_and_a_null_one_clears_it() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(session))
        state = reduceHostFrame(state, ServerFrame.LimitWait(session = "s1", wait = wait))
        assertEquals(wait, state.sessions.single().limitWait)
        state = reduceHostFrame(state, ServerFrame.LimitWait(session = "s1", wait = null))
        assertNull(state.sessions.single().limitWait)
    }

    @Test fun other_sessions_are_left_alone() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(session))
        state = reduceHostFrame(state, ServerFrame.SessionAdded(session.copy(id = "s2", limitWait = wait)))
        state = reduceHostFrame(state, ServerFrame.LimitWait(session = "s1", wait = null))
        assertEquals(wait, state.sessions.first { it.id == "s2" }.limitWait)
    }
}
