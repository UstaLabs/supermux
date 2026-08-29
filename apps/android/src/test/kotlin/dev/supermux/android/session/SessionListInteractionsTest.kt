package dev.supermux.android.session

import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.SectionKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionListInteractionsTest {
    private fun row(
        id: String,
        project: String,
        section: SectionKey = SectionKey.IN_PROGRESS,
        muted: Boolean = false,
    ) = SessionInfo(
        id = id,
        name = id,
        workdir = project,
        repo_root = project,
        agent = "claude",
        userStatus = section.wire,
        mute = muted,
    )

    @Test
    fun moveWithinScope_returnsImmediateWorkingOrder() {
        val rows = listOf(row("a", "/p"), row("b", "/p"), row("c", "/p"))

        assertEquals(
            listOf("b", "c", "a"),
            moveWithinScope(rows, emptyMap(), "a", "c")?.orderedIds,
        )
    }

    @Test
    fun moveWithinScope_rejectsDifferentProjectsAndSections() {
        val rows = listOf(
            row("a", "/one"),
            row("b", "/two"),
            row("c", "/one", SectionKey.DRAFT),
        )

        assertNull(moveWithinScope(rows, emptyMap(), "a", "b"))
        assertNull(moveWithinScope(rows, emptyMap(), "a", "c"))
    }

    @Test
    fun moveWithinScope_flatModeAllowsDifferentProjectsWithinOneSection() {
        val rows = listOf(row("a", "/one"), row("b", "/two"), row("c", "/one"))

        assertEquals(
            listOf("b", "c", "a"),
            moveWithinScope(
                rows = rows,
                workingOrders = emptyMap(),
                fromId = "a",
                toId = "c",
                projectScoped = false,
            )?.orderedIds,
        )
    }

    @Test
    fun applyWorkingOrders_reordersOnlyMatchingScopeSlots() {
        val rows = listOf(
            row("a", "/one"),
            row("x", "/two"),
            row("b", "/one"),
            row("y", "/two"),
        )
        val scope = reorderScope(rows.first())

        assertEquals(
            listOf("b", "x", "a", "y"),
            applyWorkingOrders(rows, mapOf(scope to listOf("b", "a"))).map { it.id },
        )
    }

    @Test
    fun swipeActions_matchEachSessionSection() {
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Mute, SessionSwipeAction.Settle),
            sessionSwipeActions(row("a", "/p")),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Unmute, SessionSwipeAction.Settle),
            sessionSwipeActions(row("a", "/p", muted = true)),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Edit, SessionSwipeAction.Discard),
            sessionSwipeActions(row("d", "/p", SectionKey.DRAFT)),
        )
        assertEquals(
            SessionSwipeActions(SessionSwipeAction.Activate, null),
            sessionSwipeActions(row("s", "/p", SectionKey.SETTLED)),
        )
    }

    @Test
    fun dragWorkingState_commitsChangedOrderOnce() {
        val scope = SessionReorderScope("/p", SectionKey.IN_PROGRESS)
        val state = SessionDragWorkingState()
        state.begin(scope, listOf("a", "b"))
        state.move(listOf("b", "a"))

        assertEquals(SessionReorderMove(scope, listOf("b", "a")), state.finish(commit = true))
        assertNull(state.finish(commit = true))
    }

    @Test
    fun dragWorkingState_cancelDoesNotCommit() {
        val scope = SessionReorderScope("/p", SectionKey.IN_PROGRESS)
        val state = SessionDragWorkingState()
        state.begin(scope, listOf("a", "b"))
        state.move(listOf("b", "a"))

        assertNull(state.finish(commit = false))
        assertNull(state.finish(commit = true))
    }

    @Test
    fun dragWorkingState_beginIfIdlePreservesOriginalOrderAcrossRecomposition() {
        val scope = SessionReorderScope("/p", SectionKey.IN_PROGRESS)
        val state = SessionDragWorkingState()
        state.beginIfIdle(scope, listOf("a", "b"))
        state.move(listOf("b", "a"))
        state.beginIfIdle(scope, listOf("b", "a"))

        assertEquals(SessionReorderMove(scope, listOf("b", "a")), state.finish(commit = true))
    }

    private fun chatView(id: String, sessionId: String) = ViewDto(
        id = id,
        workspaceId = "w",
        kind = "chat",
        state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
    )

    private fun workspace(
        id: String,
        views: List<ViewDto>,
        activeViewId: String? = null,
        primarySessionId: String? = null,
        workdir: String = "/p",
    ) = WorkspaceDto(
        id = id,
        name = id,
        workdir = workdir,
        activeViewId = activeViewId,
        primarySessionId = primarySessionId,
        views = views,
    )

    @Test
    fun tapWorkspace_opensActiveChatViewSession() {
        val w = workspace(
            "w1",
            views = listOf(chatView("v1", "s1"), chatView("v2", "s2")),
            activeViewId = "v2",
            primarySessionId = "s1",
        )
        assertEquals("s2", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun tapWorkspace_nonChatActiveView_opensFirstChat() {
        val w = workspace(
            "w1",
            views = listOf(
                ViewDto(id = "term", workspaceId = "w1", kind = "terminal"),
                chatView("v1", "s1"),
            ),
            activeViewId = "term",
            primarySessionId = "primary",
        )
        assertEquals("s1", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun tapWorkspace_noChatView_opensPrimarySession() {
        val w = workspace(
            "w1",
            views = emptyList(),
            primarySessionId = "primary",
        )
        assertEquals("primary", resolveWorkspaceOpenSessionId(w))
    }

    @Test
    fun tapChild_opensThatSessionId() {
        assertEquals("s2", resolveWorkspaceChildOpenSessionId(workspaceId = "w1", sessionId = "s2"))
    }

    @Test
    fun reorderWorkspaces_emitsWorkspaceIds() {
        val a = workspace("wa", emptyList())
        val b = workspace("wb", emptyList())
        val c = workspace("wc", emptyList())
        val move = moveWorkspaceWithinScope(
            rows = listOf(a, b, c),
            workingOrders = emptyMap(),
            scopeKey = WORKSPACE_FLAT_SCOPE,
            fromId = "wa",
            toId = "wc",
        )
        assertEquals(listOf("wb", "wc", "wa"), move?.orderedIds)
    }
}
