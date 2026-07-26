package dev.supermux.session

/**
 * Move [from] to [to] within [ids] (inclusive indices). Returns a new list, or
 * the original when the move is a no-op / out of range.
 */
fun moveId(ids: List<String>, from: Int, to: Int): List<String> {
    if (from == to) return ids
    if (from !in ids.indices || to !in ids.indices) return ids
    val out = ids.toMutableList()
    val item = out.removeAt(from)
    out.add(to, item)
    return out
}

/**
 * Given a section's ordered ids after a drag, return the same ids (identity).
 * Callers persist via PATCH /sessions/reorder.
 */
fun orderedIdsAfterDrop(current: List<String>, draggedId: String, targetIndex: Int): List<String> {
    val from = current.indexOf(draggedId)
    if (from < 0) return current
    val to = targetIndex.coerceIn(0, current.lastIndex)
    return moveId(current, from, to)
}
