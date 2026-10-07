package dev.supermux.workspace

import dev.supermux.proto.LayoutNodeDto

/**
 * Conversion between the wire type ([LayoutNodeDto], owned by kotlinx.serialization)
 * and the domain type ([LayoutNode], owned by LayoutTree.kt).
 *
 * They are separate so a wire-format change cannot silently change what a drag
 * does. This file is the only place that knows about both.
 */

fun LayoutNode.toDto(): LayoutNodeDto = when (this) {
    is LayoutNode.Group -> LayoutNodeDto.Group(id = id, viewIds = viewIds, activeViewId = activeViewId)
    is LayoutNode.Split -> LayoutNodeDto.Split(
        direction = direction,
        sizes = sizes,
        children = children.map { it.toDto() },
    )
}

fun LayoutNodeDto.toDomain(): LayoutNode = when (this) {
    is LayoutNodeDto.Group -> LayoutNode.Group(
        id = id,
        viewIds = viewIds,
        // The broker always sends activeViewId, but do not carry a null into the
        // UI when it does not: the first tab is the sane default everywhere.
        activeViewId = activeViewId ?: viewIds.firstOrNull(),
    )
    is LayoutNodeDto.Split -> LayoutNode.Split(
        direction = direction,
        sizes = sizes,
        children = children.map { it.toDomain() },
    )
}

fun LayoutNodeDto?.toDomainOrNull(): LayoutNode? = this?.toDomain()
