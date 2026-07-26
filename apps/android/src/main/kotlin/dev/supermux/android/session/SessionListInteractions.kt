package dev.supermux.android.session

import dev.supermux.proto.SessionInfo
import dev.supermux.session.SectionKey
import dev.supermux.session.moveId
import dev.supermux.session.sectionKey

data class SessionReorderScope(
    val project: String,
    val section: SectionKey,
)

data class SessionReorderMove(
    val scope: SessionReorderScope,
    val orderedIds: List<String>,
)

enum class SessionSwipeAction {
    Mute,
    Unmute,
    Settle,
    Edit,
    Discard,
    Activate,
}

data class SessionSwipeActions(
    val start: SessionSwipeAction?,
    val end: SessionSwipeAction?,
)

fun reorderScope(session: SessionInfo) = SessionReorderScope(
    project = session.repo_root ?: session.workdir,
    section = session.sectionKey(),
)

fun moveWithinScope(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
    fromId: String,
    toId: String,
): SessionReorderMove? {
    val from = rows.firstOrNull { it.id == fromId } ?: return null
    val to = rows.firstOrNull { it.id == toId } ?: return null
    val scope = reorderScope(from)
    if (reorderScope(to) != scope) return null

    val ids = workingOrders[scope]
        ?: rows.filter { reorderScope(it) == scope }.map { it.id }
    val fromIndex = ids.indexOf(fromId)
    val toIndex = ids.indexOf(toId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null

    return SessionReorderMove(scope, moveId(ids, fromIndex, toIndex))
}

fun applyWorkingOrders(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
): List<SessionInfo> {
    val byId = rows.associateBy { it.id }
    val queues = rows.groupBy(::reorderScope).mapValues { (scope, scopedRows) ->
        val working = workingOrders[scope].orEmpty()
        val ordered = working.mapNotNull(byId::get) +
            scopedRows.filterNot { it.id in working }
        ordered.iterator()
    }
    return rows.map { row -> queues.getValue(reorderScope(row)).next() }
}

fun sessionSwipeActions(session: SessionInfo): SessionSwipeActions = when (session.sectionKey()) {
    SectionKey.IN_PROGRESS -> SessionSwipeActions(
        start = if (session.mute == true) SessionSwipeAction.Unmute else SessionSwipeAction.Mute,
        end = SessionSwipeAction.Settle,
    )
    SectionKey.DRAFT -> SessionSwipeActions(
        start = SessionSwipeAction.Edit,
        end = SessionSwipeAction.Discard,
    )
    SectionKey.SETTLED -> SessionSwipeActions(
        start = SessionSwipeAction.Activate,
        end = null,
    )
}

class SessionDragWorkingState {
    private var scope: SessionReorderScope? = null
    private var original: List<String> = emptyList()
    private var current: List<String> = emptyList()

    fun begin(scope: SessionReorderScope, orderedIds: List<String>) {
        this.scope = scope
        original = orderedIds
        current = orderedIds
    }

    fun move(orderedIds: List<String>) {
        if (scope != null) current = orderedIds
    }

    fun finish(commit: Boolean): SessionReorderMove? {
        val finishedScope = scope
        val result = if (commit && finishedScope != null && current != original) {
            SessionReorderMove(finishedScope, current)
        } else {
            null
        }
        scope = null
        original = emptyList()
        current = emptyList()
        return result
    }
}
