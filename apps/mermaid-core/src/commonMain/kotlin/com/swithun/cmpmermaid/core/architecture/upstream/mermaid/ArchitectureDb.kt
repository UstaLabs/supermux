package com.swithun.cmpmermaid.core.architecture.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidArchitectureOptions
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/architecture/architectureDb.ts -> ArchitectureDB.
 */
internal class ArchitectureDb(
    val config: MermaidArchitectureOptions,
    frontmatterTitle: String?,
) {
    private val nodes = linkedMapOf<String, ArchitectureNode>()
    private val groups = linkedMapOf<String, ArchitectureGroup>()
    private val edges = mutableListOf<ArchitectureEdge>()
    private val layoutHints = mutableListOf<ArchitectureLayoutHint>()
    private val registeredIds = linkedMapOf<String, RegisteredKind>()
    private var dataStructures: ArchitectureDataStructures? = null

    var diagramTitle: String? = frontmatterTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }

    fun addService(
        service: ArchitectureService,
        line: Int = 1,
        column: Int = 1,
    ): GMResult<Unit, MermaidError> {
        registeredIds[service.id]?.let { kind ->
            return parseError(
                line,
                column,
                "The service id [${service.id}] is already in use by another ${kind.sourceName}",
            )
        }
        when (
            val parentValidation = validateParent(
                elementKind = "service",
                id = service.id,
                parent = service.parent,
                line = line,
                column = column,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return parentValidation
        }
        registeredIds[service.id] = RegisteredKind.Node
        nodes[service.id] = service
        return GMResult.Ok(Unit)
    }

    fun addJunction(
        junction: ArchitectureJunction,
        line: Int = 1,
        column: Int = 1,
    ): GMResult<Unit, MermaidError> {
        registeredIds[junction.id]?.let { kind ->
            return parseError(
                line,
                column,
                "The junction id [${junction.id}] is already in use by another ${kind.sourceName}",
            )
        }
        when (
            val parentValidation = validateParent(
                elementKind = "junction",
                id = junction.id,
                parent = junction.parent,
                line = line,
                column = column,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return parentValidation
        }
        registeredIds[junction.id] = RegisteredKind.Node
        nodes[junction.id] = junction
        return GMResult.Ok(Unit)
    }

    fun addGroup(
        group: ArchitectureGroup,
        line: Int = 1,
        column: Int = 1,
    ): GMResult<Unit, MermaidError> {
        registeredIds[group.id]?.let { kind ->
            return parseError(
                line,
                column,
                "The group id [${group.id}] is already in use by another ${kind.sourceName}",
            )
        }
        when (
            val parentValidation = validateParent(
                elementKind = "group",
                id = group.id,
                parent = group.parent,
                line = line,
                column = column,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return parentValidation
        }
        registeredIds[group.id] = RegisteredKind.Group
        groups[group.id] = group
        return GMResult.Ok(Unit)
    }

    fun addEdge(
        edge: ArchitectureEdge,
        line: Int = 1,
        column: Int = 1,
    ): GMResult<Unit, MermaidError> {
        if (edge.lhsId !in nodes && edge.lhsId !in groups) {
            return parseError(
                line,
                column,
                "The left-hand id [${edge.lhsId}] does not yet exist. " +
                    "Please create the service/group before declaring an edge to it.",
            )
        }
        if (edge.rhsId !in nodes && edge.rhsId !in groups) {
            return parseError(
                line,
                column,
                "The right-hand id [${edge.rhsId}] does not yet exist. " +
                    "Please create the service/group before declaring an edge to it.",
            )
        }

        val lhsGroupId = nodes[edge.lhsId]?.parent
        val rhsGroupId = nodes[edge.rhsId]?.parent
        if (edge.lhsGroup && lhsGroupId != null && lhsGroupId == rhsGroupId) {
            return parseError(
                line,
                column,
                "The left-hand id [${edge.lhsId}] is modified to traverse the group " +
                    "boundary, but the edge does not pass through two groups.",
            )
        }
        if (edge.rhsGroup && rhsGroupId != null && lhsGroupId == rhsGroupId) {
            return parseError(
                line,
                column,
                "The right-hand id [${edge.rhsId}] is modified to traverse the group " +
                    "boundary, but the edge does not pass through two groups.",
            )
        }

        edges += edge
        val lhsNode = nodes[edge.lhsId]
        val rhsNode = nodes[edge.rhsId]
        if (lhsNode != null && rhsNode != null) {
            lhsNode.edges += edge
            rhsNode.edges += edge
        }
        return GMResult.Ok(Unit)
    }

    fun addLayoutHint(
        hint: ArchitectureLayoutHint,
        line: Int = 1,
        column: Int = 1,
    ): GMResult<Unit, MermaidError> {
        if (hint.members.size < 2) {
            return parseError(
                line,
                column,
                "An align directive requires at least two members; got ${hint.members.size}",
            )
        }
        val seen = mutableSetOf<String>()
        hint.members.forEach { id ->
            if (registeredIds[id] != RegisteredKind.Node) {
                return parseError(
                    line,
                    column,
                    "align ${hint.direction.sourceName} references [$id], " +
                        "which is not a service or junction",
                )
            }
            if (!seen.add(id)) {
                return parseError(
                    line,
                    column,
                    "align ${hint.direction.sourceName} lists [$id] more than once",
                )
            }
        }
        layoutHints += hint
        return GMResult.Ok(Unit)
    }

    fun getServices(): List<ArchitectureService> =
        nodes.values.filterIsInstance<ArchitectureService>()

    fun getJunctions(): List<ArchitectureJunction> =
        nodes.values.filterIsInstance<ArchitectureJunction>()

    fun getNodes(): List<ArchitectureNode> = nodes.values.toList()

    fun getNode(id: String): ArchitectureNode? = nodes[id]

    fun getGroups(): List<ArchitectureGroup> = groups.values.toList()

    fun getEdges(): List<ArchitectureEdge> = edges.toList()

    fun getLayoutHints(): List<ArchitectureLayoutHint> = layoutHints.toList()

    fun getDataStructures(): GMResult<ArchitectureDataStructures, MermaidError> {
        dataStructures?.let { return GMResult.Ok(it) }

        val groupAlignments = linkedMapOf<String, ArchitectureAlignment>()
        val adjacencyList =
            linkedMapOf<String, Map<ArchitectureDirectionPair, String>>()
        nodes.forEach { (id, node) ->
            val directionMap = linkedMapOf<ArchitectureDirectionPair, String>()
            node.edges.forEach { edge ->
                val lhsGroupId = nodes[edge.lhsId]?.parent
                val rhsGroupId = nodes[edge.rhsId]?.parent
                if (
                    lhsGroupId != null &&
                    rhsGroupId != null &&
                    lhsGroupId != rhsGroupId
                ) {
                    val alignment = getArchitectureDirectionAlignment(
                        edge.lhsDir,
                        edge.rhsDir,
                    )
                    if (alignment != ArchitectureAlignment.Bend) {
                        groupAlignments[
                            architectureGroupAlignmentKey(lhsGroupId, rhsGroupId)
                        ] = alignment
                    }
                }

                val pair = if (edge.lhsId == id) {
                    getArchitectureDirectionPair(edge.lhsDir, edge.rhsDir)
                } else {
                    getArchitectureDirectionPair(edge.rhsDir, edge.lhsDir)
                }
                if (pair != null) {
                    directionMap[pair] = if (edge.lhsId == id) edge.rhsId else edge.lhsId
                }
            }
            adjacencyList[id] = directionMap
        }

        val visited = mutableSetOf<String>()
        val notVisited = adjacencyList.keys.toMutableSet()
        val spatialMaps = mutableListOf<Map<String, ArchitecturePosition>>()
        while (notVisited.isNotEmpty()) {
            val startingId = notVisited.first()
            val spatialMap = linkedMapOf(
                startingId to ArchitecturePosition(x = 0, y = 0),
            )
            val queue = ArrayDeque<String>()
            queue.addLast(startingId)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                visited += id
                notVisited -= id
                val adjacency = adjacencyList[id]
                    ?: return GMResult.Err(
                        MermaidError.Layout(
                            "BFS error: adjacency list for id $id not found. " +
                                "Please report this as a bug.",
                        ),
                    )
                val position = spatialMap[id]
                    ?: return GMResult.Err(
                        MermaidError.Layout(
                            "BFS error: position for id $id not found in spatial map. " +
                                "Please report this as a bug.",
                        ),
                    )
                adjacency.forEach { (directionPair, adjacentId) ->
                    if (adjacentId !in visited) {
                        spatialMap[adjacentId] =
                            shiftPositionByArchitectureDirectionPair(
                                position,
                                directionPair,
                            )
                        queue.addLast(adjacentId)
                    }
                }
            }
            spatialMaps += spatialMap
        }

        return GMResult.Ok(
            ArchitectureDataStructures(
                adjacencyList = adjacencyList,
                spatialMaps = spatialMaps,
                groupAlignments = groupAlignments,
            ).also { dataStructures = it },
        )
    }

    private fun validateParent(
        elementKind: String,
        id: String,
        parent: String?,
        line: Int,
        column: Int,
    ): GMResult<Unit, MermaidError> {
        if (parent == null) {
            return GMResult.Ok(Unit)
        }
        if (id == parent) {
            return parseError(
                line,
                column,
                "The $elementKind [$id] cannot be placed within itself",
            )
        }
        val parentKind = registeredIds[parent]
            ?: return parseError(
                line,
                column,
                "The $elementKind [$id]'s parent does not exist. Please make sure " +
                    "the parent is created before this $elementKind",
            )
        if (parentKind == RegisteredKind.Node) {
            return parseError(
                line,
                column,
                "The $elementKind [$id]'s parent is not a group",
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line,
            column = column,
            message = message,
        ),
    )

    private enum class RegisteredKind(
        val sourceName: String,
    ) {
        Node("node"),
        Group("group"),
    }
}

private val ArchitectureAlignmentDirection.sourceName: String
    get() = when (this) {
        ArchitectureAlignmentDirection.Row -> "row"
        ArchitectureAlignmentDirection.Column -> "column"
    }
