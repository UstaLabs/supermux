package com.swithun.cmpmermaid.core.architecture

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureAlignment
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureAlignmentDirection
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureDataStructures
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureDb
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureDirection
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureGroup
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureJunction
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureLayoutHint
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitecturePosition
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureService
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.architectureGroupAlignmentKey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

internal data class ArchitectureLayoutResult(
    val nodeCenters: Map<String, ScenePoint>,
    val groupBounds: Map<String, SceneRect>,
)

/**
 * Native translation adapter for Mermaid.js 12.0.0 architectureRenderer.ts ->
 * layoutArchitecture(), getAlignments(), and getRelativeConstraints().
 *
 * Mermaid delegates force integration to cytoscape-fcose 2.2.0. This adapter
 * preserves Mermaid's seeded inputs, compound grouping, edge parameters, and
 * hard alignment/relative-placement constraints in pure Kotlin.
 */
internal object ArchitectureFcoseLayout {
    fun layout(
        db: ArchitectureDb,
    ): GMResult<ArchitectureLayoutResult, MermaidError> {
        val nodes = db.getNodes()
        if (nodes.isEmpty()) {
            return GMResult.Ok(
                ArchitectureLayoutResult(
                    nodeCenters = emptyMap(),
                    groupBounds = emptyMap(),
                ),
            )
        }
        val config = db.config
        if (
            !config.iconSize.isFinite() ||
            !config.padding.isFinite() ||
            config.iconSize <= 0f ||
            config.padding < 0f
        ) {
            return layoutError("Architecture iconSize must be positive and padding non-negative")
        }
        val structures = when (val result = db.getDataStructures()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val constraints = when (
            val result = buildConstraints(
                db = db,
                structures = structures,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val workNodes = initializeNodes(db, structures)
        integrateForces(
            db = db,
            nodes = workNodes,
            constraints = constraints,
        )
        resolveNodeOverlaps(
            nodes = workNodes,
            constraints = constraints,
            separation = config.nodeSeparation.toDouble(),
        )

        val groups = db.getGroups()
        val groupBounds = resolveCompoundGroups(
            nodes = workNodes,
            groups = groups,
            padding = config.padding.toDouble(),
            nodeSeparation = config.nodeSeparation.toDouble(),
            groupAlignments = structures.groupAlignments,
            iconSize = config.iconSize.toDouble(),
            fontSize = config.fontSize.toDouble(),
        )
        return GMResult.Ok(
            ArchitectureLayoutResult(
                nodeCenters = workNodes.mapValues { (_, node) ->
                    ScenePoint(node.x.toFloat(), node.y.toFloat())
                },
                groupBounds = groupBounds.mapValues { (_, bounds) -> bounds.toSceneRect() },
            ),
        )
    }

    private fun initializeNodes(
        db: ArchitectureDb,
        structures: ArchitectureDataStructures,
    ): LinkedHashMap<String, WorkNode> {
        val config = db.config
        val gap = config.idealEdgeLengthMultiplier * config.iconSize
        val random = if (config.seed == 0f) {
            Random.Default
        } else {
            Mulberry32(config.seed.toUInt())
        }
        val positions = linkedMapOf<String, ScenePoint>()
        var componentLeft = 0f
        structures.spatialMaps.forEach { spatialMap ->
            val raw = spatialMap.mapValues { (_, position) ->
                if (config.randomize) {
                    ScenePoint(
                        x = random.nextFloat() * gap * 2f,
                        y = random.nextFloat() * gap * 2f,
                    )
                } else {
                    ScenePoint(
                        x = position.x * gap,
                        y = -position.y * gap,
                    )
                }
            }
            val minX = raw.values.minOfOrNull(ScenePoint::x) ?: 0f
            val maxX = raw.values.maxOfOrNull(ScenePoint::x) ?: 0f
            val minY = raw.values.minOfOrNull(ScenePoint::y) ?: 0f
            raw.forEach { (id, point) ->
                positions[id] = ScenePoint(
                    x = componentLeft + point.x - minX,
                    y = point.y - minY,
                )
            }
            componentLeft += max(config.iconSize, maxX - minX + config.nodeSeparation)
        }

        return linkedMapOf<String, WorkNode>().also { result ->
            db.getNodes().forEachIndexed { index, node ->
                val point = positions[node.id] ?: ScenePoint(
                    x = index * (config.iconSize + config.nodeSeparation),
                    y = 0f,
                )
                result[node.id] = WorkNode(
                    id = node.id,
                    parent = node.parent,
                    x = point.x.toDouble(),
                    y = point.y.toDouble(),
                    anchorX = point.x.toDouble(),
                    anchorY = point.y.toDouble(),
                    size = config.iconSize.toDouble(),
                    hasLabel = node is ArchitectureService && node.title != null,
                    isJunction = node is ArchitectureJunction,
                )
            }
        }
    }

    private fun buildConstraints(
        db: ArchitectureDb,
        structures: ArchitectureDataStructures,
    ): GMResult<Constraints, MermaidError> {
        val hints = db.getLayoutHints()
        val horizontalOwners = mutableMapOf<String, Int>()
        val verticalOwners = mutableMapOf<String, Int>()
        hints.forEachIndexed { index, hint ->
            val owners = if (hint.direction == ArchitectureAlignmentDirection.Row) {
                horizontalOwners
            } else {
                verticalOwners
            }
            hint.members.forEach { member ->
                val previous = owners.put(member, index)
                if (previous != null && previous != index) {
                    return layoutError(
                        "Architecture layout failed: a declared `align row|column` " +
                            "directive overlaps on node [$member] along the same axis.",
                    )
                }
            }
        }

        val declaredMembers = hints
            .flatMapTo(mutableSetOf(), ArchitectureLayoutHint::members)
        val horizontal = mutableListOf<List<String>>()
        val vertical = mutableListOf<List<String>>()
        structures.spatialMaps.forEach { spatialMap ->
            horizontal += alignmentGroups(
                db = db,
                spatialMap = spatialMap,
                coordinate = ArchitecturePosition::y,
                expected = ArchitectureAlignment.Horizontal,
                groupAlignments = structures.groupAlignments,
            ).filter { group -> group.none(declaredMembers::contains) }
            vertical += alignmentGroups(
                db = db,
                spatialMap = spatialMap,
                coordinate = ArchitecturePosition::x,
                expected = ArchitectureAlignment.Vertical,
                groupAlignments = structures.groupAlignments,
            ).filter { group -> group.none(declaredMembers::contains) }
        }
        hints.forEach { hint ->
            if (hint.direction == ArchitectureAlignmentDirection.Row) {
                horizontal += hint.members
            } else {
                vertical += hint.members
            }
        }

        val gap = db.config.idealEdgeLengthMultiplier * db.config.iconSize
        val relative = mutableListOf<RelativeConstraint>()
        val declaredPairs = mutableSetOf<Pair<String, String>>()
        hints.forEach { hint ->
            hint.members.zipWithNext().forEach { (first, second) ->
                declaredPairs += first to second
                declaredPairs += second to first
                relative += if (hint.direction == ArchitectureAlignmentDirection.Row) {
                    RelativeConstraint(
                        before = first,
                        after = second,
                        axis = Axis.X,
                        gap = gap.toDouble(),
                    )
                } else {
                    RelativeConstraint(
                        before = first,
                        after = second,
                        axis = Axis.Y,
                        gap = gap.toDouble(),
                    )
                }
            }
        }
        structures.spatialMaps.forEach { spatialMap ->
            val inverse = linkedMapOf<ArchitecturePosition, String>()
            spatialMap.forEach { (id, position) -> inverse[position] = id }
            val queue = ArrayDeque<ArchitecturePosition>()
            queue.addLast(ArchitecturePosition(0, 0))
            val visited = mutableSetOf<ArchitecturePosition>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                visited += current
                val currentId = inverse[current] ?: continue
                DIRECTION_SHIFTS.forEach { (direction, shift) ->
                    val adjacentPosition = ArchitecturePosition(
                        x = current.x + shift.x,
                        y = current.y + shift.y,
                    )
                    val adjacentId = inverse[adjacentPosition]
                    if (adjacentId != null && adjacentPosition !in visited) {
                        queue.addLast(adjacentPosition)
                        if ((currentId to adjacentId) !in declaredPairs) {
                            relative += direction.toRelativeConstraint(
                                adjacentId = adjacentId,
                                currentId = currentId,
                                gap = architectureRelativeGap(
                                    db = db,
                                    firstId = currentId,
                                    secondId = adjacentId,
                                    direction = direction,
                                    defaultGap = gap.toDouble(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        return GMResult.Ok(
            Constraints(
                horizontalAlignments = horizontal.distinct(),
                verticalAlignments = vertical.distinct(),
                relativePlacements = relative.distinct(),
            ),
        )
    }

    private fun alignmentGroups(
        db: ArchitectureDb,
        spatialMap: Map<String, ArchitecturePosition>,
        coordinate: (ArchitecturePosition) -> Int,
        expected: ArchitectureAlignment,
        groupAlignments: Map<String, ArchitectureAlignment>,
    ): List<List<String>> {
        val buckets = linkedMapOf<Int, LinkedHashMap<String, MutableList<String>>>()
        spatialMap.forEach { (id, position) ->
            val groupId = db.getNode(id)?.parent ?: DEFAULT_GROUP
            buckets.getOrPut(coordinate(position), ::linkedMapOf)
                .getOrPut(groupId, ::mutableListOf)
                .add(id)
        }
        return buckets.values.flatMap { byGroup ->
            if (byGroup.size == 1) {
                return@flatMap listOf(byGroup.values.first())
            }
            val remaining = byGroup.keys.toMutableSet()
            val result = mutableListOf<List<String>>()
            while (remaining.isNotEmpty()) {
                val seed = remaining.first()
                remaining -= seed
                val connected = mutableSetOf(seed)
                var changed = true
                while (changed) {
                    changed = false
                    remaining.toList().forEach { candidate ->
                        if (
                            seed == DEFAULT_GROUP ||
                            candidate == DEFAULT_GROUP ||
                            connected.any { current ->
                                groupAlignments[
                                    architectureGroupAlignmentKey(current, candidate)
                                ] == expected
                            }
                        ) {
                            connected += candidate
                            remaining -= candidate
                            changed = true
                        }
                    }
                }
                result += connected.flatMap { group -> byGroup.getValue(group) }
            }
            result
        }.filter { group -> group.size > 1 }
    }

    private fun integrateForces(
        db: ArchitectureDb,
        nodes: LinkedHashMap<String, WorkNode>,
        constraints: Constraints,
    ) {
        val config = db.config
        val edges = db.getEdges()
        val iterations = config.numIter.toInt().coerceIn(1, MAX_NATIVE_ITERATIONS)
        val separation = config.nodeSeparation.toDouble().coerceAtLeast(1.0)
        repeat(iterations) { iteration ->
            nodes.values.forEach(WorkNode::resetForces)
            edges.forEach { edge ->
                val source = nodes[edge.lhsId] ?: return@forEach
                val target = nodes[edge.rhsId] ?: return@forEach
                val sameGroup = source.parent == target.parent
                val idealLength = if (sameGroup) {
                    config.idealEdgeLengthMultiplier * config.iconSize
                } else {
                    0.5f * config.iconSize
                }.toDouble()
                val elasticity = if (sameGroup) {
                    config.edgeElasticity.toDouble()
                } else {
                    CROSS_GROUP_ELASTICITY
                }
                val dx = target.x - source.x
                val dy = target.y - source.y
                val distance = hypot(dx, dy).coerceAtLeast(0.001)
                val force = (distance - idealLength) * elasticity * SPRING_SCALE
                val forceX = force * dx / distance
                val forceY = force * dy / distance
                source.forceX += forceX
                source.forceY += forceY
                target.forceX -= forceX
                target.forceY -= forceY
            }

            val nodeList = nodes.values.toList()
            for (firstIndex in 0 until nodeList.lastIndex) {
                for (secondIndex in firstIndex + 1 until nodeList.size) {
                    applyRepulsion(
                        first = nodeList[firstIndex],
                        second = nodeList[secondIndex],
                        separation = separation,
                    )
                }
            }
            nodes.values.forEach { node ->
                node.forceX += (node.anchorX - node.x) * ANCHOR_STRENGTH
                node.forceY += (node.anchorY - node.y) * ANCHOR_STRENGTH
            }

            val cooling = max(
                MIN_COOLING,
                1.0 - iteration.toDouble() / iterations.toDouble(),
            )
            var displacement = 0.0
            nodes.values.forEach { node ->
                val dx = (node.forceX * cooling).coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)
                val dy = (node.forceY * cooling).coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)
                node.x += dx
                node.y += dy
                displacement += abs(dx) + abs(dy)
            }
            projectConstraints(nodes, constraints)
            if (iteration > MIN_ITERATIONS && displacement < CONVERGENCE_THRESHOLD * nodes.size) {
                return
            }
        }
    }

    private fun applyRepulsion(
        first: WorkNode,
        second: WorkNode,
        separation: Double,
    ) {
        var dx = second.x - first.x
        var dy = second.y - first.y
        if (dx == 0.0 && dy == 0.0) {
            dx = if (first.id < second.id) 1.0 else -1.0
            dy = 0.5
        }
        val distance = hypot(dx, dy).coerceAtLeast(0.001)
        val desired = (first.size + second.size) / 2.0 + separation
        if (distance >= desired) {
            return
        }
        val force = (desired - distance) * REPULSION_SCALE
        val forceX = force * dx / distance
        val forceY = force * dy / distance
        first.forceX -= forceX
        first.forceY -= forceY
        second.forceX += forceX
        second.forceY += forceY
    }

    private fun projectConstraints(
        nodes: Map<String, WorkNode>,
        constraints: Constraints,
    ) {
        constraints.horizontalAlignments.forEach { ids ->
            val members = ids.mapNotNull(nodes::get)
            if (members.size > 1) {
                val average = members.sumOf(WorkNode::y) / members.size
                members.forEach { node -> node.y = average }
            }
        }
        constraints.verticalAlignments.forEach { ids ->
            val members = ids.mapNotNull(nodes::get)
            if (members.size > 1) {
                val average = members.sumOf(WorkNode::x) / members.size
                members.forEach { node -> node.x = average }
            }
        }
        constraints.relativePlacements.forEach { constraint ->
            val before = nodes[constraint.before] ?: return@forEach
            val after = nodes[constraint.after] ?: return@forEach
            val current = if (constraint.axis == Axis.X) {
                after.x - before.x
            } else {
                after.y - before.y
            }
            if (current < constraint.gap) {
                val correction = (constraint.gap - current) / 2.0
                if (constraint.axis == Axis.X) {
                    before.x -= correction
                    after.x += correction
                } else {
                    before.y -= correction
                    after.y += correction
                }
            }
        }
    }

    private fun resolveNodeOverlaps(
        nodes: Map<String, WorkNode>,
        constraints: Constraints,
        separation: Double,
    ) {
        repeat(OVERLAP_PASSES) {
            var moved = false
            val values = nodes.values.toList()
            for (firstIndex in 0 until values.lastIndex) {
                for (secondIndex in firstIndex + 1 until values.size) {
                    val first = values[firstIndex]
                    val second = values[secondIndex]
                    val constrainedGap = constraints.relativePlacements
                        .firstOrNull { constraint ->
                            (
                                constraint.before == first.id &&
                                    constraint.after == second.id
                                ) || (
                                constraint.before == second.id &&
                                    constraint.after == first.id
                                )
                        }
                        ?.gap
                    val required = if (
                        (first.isJunction || second.isJunction) &&
                        constrainedGap != null
                    ) {
                        constrainedGap
                    } else {
                        (first.size + second.size) / 2.0 + separation
                    }
                    val overlapX = required - abs(second.x - first.x)
                    val overlapY = required - abs(second.y - first.y)
                    if (overlapX <= 0.0 || overlapY <= 0.0) {
                        continue
                    }
                    val sameHorizontal =
                        constraints.horizontalAlignments.shareAlignment(
                            first.id,
                            second.id,
                        )
                    val sameVertical =
                        constraints.verticalAlignments.shareAlignment(
                            first.id,
                            second.id,
                        )
                    if (sameHorizontal || (!sameVertical && overlapX <= overlapY)) {
                        val shift = overlapX / 2.0
                        val sign = if (second.x >= first.x) 1.0 else -1.0
                        first.x -= shift * sign
                        second.x += shift * sign
                    } else {
                        val shift = overlapY / 2.0
                        val sign = if (second.y >= first.y) 1.0 else -1.0
                        first.y -= shift * sign
                        second.y += shift * sign
                    }
                    moved = true
                }
            }
            projectConstraints(nodes, constraints)
            if (!moved) {
                return
            }
        }
    }

    private fun List<List<String>>.shareAlignment(
        firstId: String,
        secondId: String,
    ): Boolean = any { alignment ->
        firstId in alignment && secondId in alignment
    }

    private fun resolveCompoundGroups(
        nodes: LinkedHashMap<String, WorkNode>,
        groups: List<ArchitectureGroup>,
        padding: Double,
        nodeSeparation: Double,
        groupAlignments: Map<String, ArchitectureAlignment>,
        iconSize: Double,
        fontSize: Double,
    ): Map<String, Bounds> {
        val groupsByParent = groups.groupBy(ArchitectureGroup::parent)
        val descendants = groups.associate { group ->
            group.id to nodes.values.filter { node ->
                isDescendantOf(node.parent, group.id, groups)
            }.mapTo(linkedSetOf(), WorkNode::id)
        }
        repeat(GROUP_OVERLAP_PASSES) {
            val bounds = calculateGroupBounds(
                nodes = nodes,
                groups = groups,
                padding = padding,
                iconSize = iconSize,
                fontSize = fontSize,
            )
            var moved = false
            groupsByParent.values.forEach { siblings ->
                for (firstIndex in 0 until siblings.lastIndex) {
                    for (secondIndex in firstIndex + 1 until siblings.size) {
                        val first = siblings[firstIndex]
                        val second = siblings[secondIndex]
                        val firstBounds = bounds[first.id] ?: continue
                        val secondBounds = bounds[second.id] ?: continue
                        val overlapX = min(firstBounds.right, secondBounds.right) -
                            max(firstBounds.left, secondBounds.left) +
                            nodeSeparation
                        val overlapY = min(firstBounds.bottom, secondBounds.bottom) -
                            max(firstBounds.top, secondBounds.top) +
                            nodeSeparation
                        if (overlapX <= 0.0 || overlapY <= 0.0) {
                            continue
                        }
                        val alignment = groupAlignments[
                            architectureGroupAlignmentKey(first.id, second.id)
                        ]
                        val moveX = alignment == ArchitectureAlignment.Horizontal ||
                            alignment == null && overlapX <= overlapY
                        val amount = if (moveX) overlapX else overlapY
                        val firstCenter = if (moveX) firstBounds.centerX else firstBounds.centerY
                        val secondCenter = if (moveX) secondBounds.centerX else secondBounds.centerY
                        val sign = if (secondCenter >= firstCenter) 1.0 else -1.0
                        shiftNodes(
                            nodes = nodes,
                            ids = descendants.getValue(second.id),
                            dx = if (moveX) amount * sign else 0.0,
                            dy = if (moveX) 0.0 else amount * sign,
                        )
                        moved = true
                    }
                }
            }
            if (!moved) {
                return bounds
            }
        }
        return calculateGroupBounds(
            nodes = nodes,
            groups = groups,
            padding = padding,
            iconSize = iconSize,
            fontSize = fontSize,
        )
    }

    private fun calculateGroupBounds(
        nodes: Map<String, WorkNode>,
        groups: List<ArchitectureGroup>,
        padding: Double,
        iconSize: Double,
        fontSize: Double,
    ): Map<String, Bounds> {
        val result = linkedMapOf<String, Bounds>()
        val groupsByParent = groups.groupBy(ArchitectureGroup::parent)

        fun calculate(group: ArchitectureGroup): Bounds {
            result[group.id]?.let { return it }
            val directNodeBounds = nodes.values
                .filter { node -> node.parent == group.id }
                .map { node ->
                    Bounds(
                        left = node.x - iconSize / 2.0,
                        top = node.y - iconSize / 2.0,
                        right = node.x + iconSize / 2.0,
                        bottom = node.y + iconSize / 2.0 +
                            if (node.hasLabel) fontSize + 2.0 else 0.0,
                    )
                }
            val childGroupBounds = groupsByParent[group.id].orEmpty().map(::calculate)
            val content = (directNodeBounds + childGroupBounds).reduceOrNull(Bounds::union)
                ?: Bounds(
                    left = 0.0,
                    top = 0.0,
                    right = iconSize,
                    bottom = iconSize,
                )
            return content.inflate(padding, padding).also { result[group.id] = it }
        }
        groups.forEach(::calculate)
        return result
    }

    private fun isDescendantOf(
        initialParent: String?,
        ancestor: String,
        groups: List<ArchitectureGroup>,
    ): Boolean {
        val parentById = groups.associate { group -> group.id to group.parent }
        var parent = initialParent
        while (parent != null) {
            if (parent == ancestor) {
                return true
            }
            parent = parentById[parent]
        }
        return false
    }

    private fun shiftNodes(
        nodes: Map<String, WorkNode>,
        ids: Set<String>,
        dx: Double,
        dy: Double,
    ) {
        ids.forEach { id ->
            nodes[id]?.let { node ->
                node.x += dx
                node.y += dy
                node.anchorX += dx
                node.anchorY += dy
            }
        }
    }

    private fun architectureRelativeGap(
        db: ArchitectureDb,
        firstId: String,
        secondId: String,
        direction: ArchitectureDirection,
        defaultGap: Double,
    ): Double {
        val includesJunction =
            db.getNode(firstId) is ArchitectureJunction ||
                db.getNode(secondId) is ArchitectureJunction
        return if (
            includesJunction &&
            direction in setOf(ArchitectureDirection.L, ArchitectureDirection.R)
        ) {
            defaultGap + db.config.iconSize
        } else {
            defaultGap
        }
    }

    private fun ArchitectureDirection.toRelativeConstraint(
        adjacentId: String,
        currentId: String,
        gap: Double,
    ): RelativeConstraint = when (this) {
        ArchitectureDirection.L -> RelativeConstraint(
            before = adjacentId,
            after = currentId,
            axis = Axis.X,
            gap = gap,
        )
        ArchitectureDirection.R -> RelativeConstraint(
            before = currentId,
            after = adjacentId,
            axis = Axis.X,
            gap = gap,
        )
        ArchitectureDirection.T -> RelativeConstraint(
            before = adjacentId,
            after = currentId,
            axis = Axis.Y,
            gap = gap,
        )
        ArchitectureDirection.B -> RelativeConstraint(
            before = currentId,
            after = adjacentId,
            axis = Axis.Y,
            gap = gap,
        )
    }

    private data class Constraints(
        val horizontalAlignments: List<List<String>>,
        val verticalAlignments: List<List<String>>,
        val relativePlacements: List<RelativeConstraint>,
    )

    private data class RelativeConstraint(
        val before: String,
        val after: String,
        val axis: Axis,
        val gap: Double,
    )

    private enum class Axis {
        X,
        Y,
    }

    private data class WorkNode(
        val id: String,
        val parent: String?,
        var x: Double,
        var y: Double,
        var anchorX: Double,
        var anchorY: Double,
        val size: Double,
        val hasLabel: Boolean,
        val isJunction: Boolean,
        var forceX: Double = 0.0,
        var forceY: Double = 0.0,
    ) {
        fun resetForces() {
            forceX = 0.0
            forceY = 0.0
        }
    }

    private data class Bounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (top + bottom) / 2.0

        fun union(other: Bounds): Bounds = Bounds(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom),
        )

        fun inflate(horizontal: Double, vertical: Double): Bounds = Bounds(
            left = left - horizontal,
            top = top - vertical,
            right = right + horizontal,
            bottom = bottom + vertical,
        )

        fun toSceneRect(): SceneRect = SceneRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
        )
    }

    /**
     * Mermaid.js 12.0.0:
     * diagrams/architecture/architectureSeed.ts -> withSeededRandom/mulberry32.
     */
    private class Mulberry32(
        seed: UInt,
    ) : Random() {
        private var state: UInt = seed

        override fun nextBits(bitCount: Int): Int {
            state += 0x6D2B79F5u
            var value = state
            value = (value xor (value shr 15)) * (value or 1u)
            value = value xor (value + (value xor (value shr 7)) * (value or 61u))
            val result = value xor (value shr 14)
            return if (bitCount == 32) {
                result.toInt()
            } else {
                (result shr (32 - bitCount)).toInt()
            }
        }
    }

    private fun layoutError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private const val DEFAULT_GROUP = "default"
    private const val CROSS_GROUP_ELASTICITY = 0.001
    private const val SPRING_SCALE = 0.02
    private const val REPULSION_SCALE = 0.08
    private const val ANCHOR_STRENGTH = 0.004
    private const val MAX_DISPLACEMENT = 12.0
    private const val MIN_COOLING = 0.04
    private const val MIN_ITERATIONS = 30
    private const val MAX_NATIVE_ITERATIONS = 600
    private const val CONVERGENCE_THRESHOLD = 0.01
    private const val OVERLAP_PASSES = 20
    private const val GROUP_OVERLAP_PASSES = 20

    private val DIRECTION_SHIFTS = linkedMapOf(
        ArchitectureDirection.L to ArchitecturePosition(-1, 0),
        ArchitectureDirection.R to ArchitecturePosition(1, 0),
        ArchitectureDirection.T to ArchitecturePosition(0, 1),
        ArchitectureDirection.B to ArchitecturePosition(0, -1),
    )
}
