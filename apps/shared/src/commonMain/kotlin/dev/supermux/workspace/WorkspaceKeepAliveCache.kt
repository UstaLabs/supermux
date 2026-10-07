package dev.supermux.workspace

const val MAX_RETAINED_WORKSPACES = 10

class WorkspaceKeepAliveCache(
    private val maxSize: Int = MAX_RETAINED_WORKSPACES,
) {
    private val retained = linkedSetOf<String>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    fun update(activeWorkspaceId: String?, liveWorkspaceIds: Set<String>): List<String> {
        val candidate = preview(activeWorkspaceId, liveWorkspaceIds)
        commit(candidate)
        return candidate
    }

    fun preview(
        activeWorkspaceId: String?,
        liveWorkspaceIds: Set<String>,
        extraIds: Set<String> = emptySet(),
    ): List<String> {
        val extras = extraIds.filter { it in liveWorkspaceIds }.toSet()
        val candidate = retained.filterTo(linkedSetOf()) { it in liveWorkspaceIds }
        extras.forEach {
            candidate.remove(it)
            candidate.add(it)
        }
        if (activeWorkspaceId != null && activeWorkspaceId in liveWorkspaceIds) {
            candidate.remove(activeWorkspaceId)
            candidate.add(activeWorkspaceId)
        }
        while (candidate.size > maxSize) {
            val evict = candidate.firstOrNull { it !in extras && it != activeWorkspaceId } ?: break
            candidate.remove(evict)
        }
        return candidate.toList()
    }

    fun commit(candidate: List<String>) {
        retained.clear()
        retained.addAll(candidate)
    }
}
