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

private const val ALL_PROJECTS_SCOPE = "__all_projects__"

fun reorderScope(
    session: SessionInfo,
    projectScoped: Boolean = true,
) = SessionReorderScope(
    project = if (projectScoped) session.repo_root ?: session.workdir else ALL_PROJECTS_SCOPE,
    section = session.sectionKey(),
)

fun moveWithinScope(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
    fromId: String,
    toId: String,
    projectScoped: Boolean = true,
): SessionReorderMove? {
    val from = rows.firstOrNull { it.id == fromId } ?: return null
    val to = rows.firstOrNull { it.id == toId } ?: return null
    val scope = reorderScope(from, projectScoped)
    if (reorderScope(to, projectScoped) != scope) return null

    val ids = workingOrders[scope]
        ?: rows.filter { reorderScope(it, projectScoped) == scope }.map { it.id }
    val fromIndex = ids.indexOf(fromId)
    val toIndex = ids.indexOf(toId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null

    return SessionReorderMove(scope, moveId(ids, fromIndex, toIndex))
}

fun applyWorkingOrders(
    rows: List<SessionInfo>,
    workingOrders: Map<SessionReorderScope, List<String>>,
    projectScoped: Boolean = true,
): List<SessionInfo> {
    val byId = rows.associateBy { it.id }
    val scopeOf: (SessionInfo) -> SessionReorderScope = { reorderScope(it, projectScoped) }
    val queues = rows.groupBy(scopeOf).mapValues { (scope, scopedRows) ->
        val working = workingOrders[scope].orEmpty()
        val ordered = working.mapNotNull(byId::get) +
            scopedRows.filterNot { it.id in working }
        ordered.iterator()
    }
    return rows.map { row -> queues.getValue(scopeOf(row)).next() }
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

    fun beginIfIdle(scope: SessionReorderScope, orderedIds: List<String>) {
        if (this.scope == null) begin(scope, orderedIds)
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
