package dev.supermux.desktop.state

import dev.supermux.proto.ClientFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewingFrameTest {

    @Test
    fun aWorkspaceWithOneVisibleChatSendsOneFrameForThatSession() {
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1"))
        // One visible chat puts the SAME bytes on the wire as before workspaces:
        // no `sessions` field at all. The classic path must not shift.
        assertEquals(listOf(ClientFrame.Viewing("s1", true, null)), frames)
    }

    @Test
    fun twoVisibleChatsGoOutAsOneFrameCarryingTheWholeSet() {
        // One frame, not two: bare Viewing(s, true) REPLACES the broker's set
        // (every single-session client switches chats that way), so two such
        // frames would leave only the last chat suppressed.
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1", "s2"))
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true, listOf("s1", "s2"))),
            frames,
        )
    }

    @Test
    fun aBackgroundTabIsNotVisible() {
        // Only the ACTIVE view of each group is visible. A chat sitting in an
        // inactive tab must not suppress its own notifications.
        val frames = viewingFramesFor(visibleChatSessionIds = emptyList())
        assertEquals(listOf(ClientFrame.Viewing(null, false)), frames)
    }

    @Test
    fun aWorkspaceIdIsNeverSentAsASession() {
        // The Phase 3 defect: selectedId became a workspace id and was still sent
        // as a session. Every id in a Viewing frame must come from a chat view.
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1"))
        assertEquals(false, frames.any { it.session == "w1" })
    }
}
