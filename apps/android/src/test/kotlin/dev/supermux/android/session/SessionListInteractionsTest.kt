package dev.supermux.android.session

import dev.supermux.proto.SessionInfo
import dev.supermux.session.SectionKey
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
}
