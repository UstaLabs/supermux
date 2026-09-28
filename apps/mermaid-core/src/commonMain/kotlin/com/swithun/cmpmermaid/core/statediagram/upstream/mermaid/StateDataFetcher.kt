package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneStrokePattern

/**
 * Kotlin translation of Mermaid 12.0.0 state/dataFetcher.ts.
 *
 * This keeps the upstream two-stage contract: the parser/DB owns statements,
 * while this adapter owns unified-renderer nodes, groups, and edges.
 */
internal object StateDataFetcher {
    fun fetch(db: StateDb): GMResult<StateRenderData, MermaidError> {
        val builder = Builder(db)
        return builder.fetch()
    }

    private class Builder(
        private val db: StateDb,
    ) {
        private val nodes = linkedMapOf<String, MutableStateRenderNode>()
        private val edges = mutableListOf<StateRenderEdge>()
        private var graphItemCount = 0
        private var nextColorIndex = 0
        private val containerColorIndex = linkedMapOf<String, Int?>()

        fun fetch(): GMResult<StateRenderData, MermaidError> {
            when (
                val result = setupDocument(
                    parent = null,
                    document = db.getRootDocument(),
                    alternate = true,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
            val groups = mutableListOf<StateRenderGroup>()
            val leaves = linkedMapOf<String, StateRenderNode>()
            nodes.values.forEach { node ->
                if (node.isGroup) {
                    if (node.labels.size > 1) {
                        return GMResult.Err(
                            MermaidError.Layout(
                                "Group nodes can only have a label. Remove the additional " +
                                    "description for node [${node.id}]",
                            ),
                        )
                    }
                    groups += StateRenderGroup(
                        id = node.id,
                        label = node.labels.firstOrNull().orEmpty(),
                        parentId = node.parentId,
                        direction = node.direction,
                        divider = node.type == StateNodeType.Divider,
                        note = node.note,
                        alternate = node.alternate,
                        styles = node.styles.toList(),
                        colorIndex = node.colorIndex,
                        position = node.position,
                    )
                } else {
                    leaves[node.id] = StateRenderNode(
                        id = node.id,
                        type = node.type,
                        labels = node.labels.toList(),
                        parentId = node.parentId,
                        styles = node.styles.toList(),
                        position = node.position,
                        noteOwner = node.noteOwner,
                    )
                }
            }
            return GMResult.Ok(
                StateRenderData(
                    direction = db.direction(),
                    nodes = leaves,
                    groups = groups,
                    edges = edges.toList(),
                ),
            )
        }

        private fun setupDocument(
            parent: StateNodeStatement?,
            document: List<StateStatement>,
            alternate: Boolean,
        ): GMResult<Unit, MermaidError> {
            document.forEach { statement ->
                when (statement) {
                    is StateNodeStatement -> {
                        when (val result = addNode(parent, statement, alternate)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return result
                        }
                    }
                    is StateRelationStatement -> {
                        when (val result = addNode(parent, statement.state1, alternate)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return result
                        }
                        when (val result = addNode(parent, statement.state2, alternate)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return result
                        }
                        edges += StateRenderEdge(
                            id = "edge$graphItemCount",
                            start = statement.state1.id,
                            end = statement.state2.id,
                            label = statement.description,
                        )
                        graphItemCount += 1
                    }
                    is StateApplyClassStatement,
                    is StateClassDefStatement,
                    is StateClickStatement,
                    is StateDirectionStatement,
                    is StateIgnoredStatement,
                    is StateStyleStatement,
                    -> Unit
                }
            }
            return GMResult.Ok(Unit)
        }

        private fun addNode(
            parent: StateNodeStatement?,
            parsed: StateNodeStatement,
            alternate: Boolean,
        ): GMResult<Unit, MermaidError> {
            val itemId = parsed.id
            if (itemId == ROOT_ID) {
                parsed.document?.let { document ->
                    return setupDocument(parsed, document, !alternate)
                }
                return GMResult.Ok(Unit)
            }

            val dbState = db.getStates()[itemId]
            val classes = dbState?.classes.orEmpty()
            val styles = buildList {
                addAll(dbState?.styles.orEmpty())
                classes.forEach { className ->
                    addAll(db.getClasses()[className]?.styles.orEmpty())
                }
            }
            val current = nodes.getOrPut(itemId) {
                MutableStateRenderNode(
                    id = itemId,
                    type = typeFor(parsed),
                    labels = mutableListOf(itemId),
                    parentId = parent?.id?.takeUnless { it == ROOT_ID },
                    styles = styles.toMutableList(),
                )
            }
            current.type = typeFor(parsed).takeUnless { it == StateNodeType.Default }
                ?: current.type
            if (styles.isNotEmpty()) {
                current.styles.clear()
                current.styles += styles
            }
            parsed.descriptions.forEach { description ->
                val normalized = description.trim()
                if (normalized.isEmpty()) return@forEach
                if (current.labels.size == 1 && current.labels.first() == itemId) {
                    current.labels[0] = normalized
                } else if (normalized !in current.labels) {
                    current.labels += normalized
                }
            }
            if (parsed.document != null) {
                current.isGroup = true
                current.direction = direction(parsed.document.orEmpty())
                current.alternate = alternate
                current.type = parsed.type
                val userStyled = classes.isNotEmpty() || styles.isNotEmpty()
                current.colorIndex = colorSlotFor(
                    node = current,
                    parent = parent,
                    userStyled = userStyled,
                )
            }
            if (parent != null && parent.id != ROOT_ID) {
                current.parentId = parent.id
            }

            parsed.note?.let { note ->
                val noteId = "$itemId----note-$graphItemCount"
                val noteGroupId = "$itemId----parent"
                // Mermaid 12.0.0: state/dataFetcher.ts creates an invisible
                // noteGroup compound around the note before Dagre layout.
                nodes[noteGroupId] = MutableStateRenderNode(
                    id = noteGroupId,
                    type = StateNodeType.Default,
                    labels = mutableListOf(note.text),
                    parentId = current.parentId,
                    styles = mutableListOf(),
                    isGroup = true,
                    position = note.position,
                    note = true,
                )
                nodes[noteId] = MutableStateRenderNode(
                    id = noteId,
                    type = StateNodeType.Default,
                    labels = mutableListOf(note.text),
                    parentId = noteGroupId,
                    styles = mutableListOf(),
                    position = note.position,
                    noteOwner = itemId,
                )
                val noteOnLeft = note.position == "left of"
                edges += StateRenderEdge(
                    id = if (noteOnLeft) "$noteId-$itemId" else "$itemId-$noteId",
                    start = if (noteOnLeft) noteId else itemId,
                    end = if (noteOnLeft) itemId else noteId,
                    pattern = SceneStrokePattern.Dashed,
                    arrow = false,
                    note = true,
                )
                graphItemCount += 1
            }

            parsed.document?.let { document ->
                return setupDocument(parsed, document, !alternate)
            }
            return GMResult.Ok(Unit)
        }

        private fun colorSlotFor(
            node: MutableStateRenderNode,
            parent: StateNodeStatement?,
            userStyled: Boolean,
        ): Int? {
            if (
                node.type == StateNodeType.Divider &&
                parent != null &&
                containerColorIndex.containsKey(parent.id)
            ) {
                return containerColorIndex[parent.id].also { inherited ->
                    containerColorIndex[node.id] = inherited
                }
            }
            val slot = nextColorIndex
            nextColorIndex += 1
            val effective = slot.takeUnless { userStyled }
            containerColorIndex[node.id] = effective
            return effective
        }

        private fun typeFor(statement: StateNodeStatement): StateNodeType = when {
            statement.start == true -> StateNodeType.Start
            statement.start == false -> StateNodeType.End
            else -> statement.type
        }

        private fun direction(document: List<StateStatement>): String =
            document.filterIsInstance<StateDirectionStatement>()
                .lastOrNull()
                ?.value
                ?: DEFAULT_NESTED_DIRECTION
    }

    private data class MutableStateRenderNode(
        val id: String,
        var type: StateNodeType,
        val labels: MutableList<String>,
        var parentId: String?,
        val styles: MutableList<String>,
        var isGroup: Boolean = false,
        var direction: String? = null,
        var alternate: Boolean = false,
        var colorIndex: Int? = null,
        var position: String? = null,
        var noteOwner: String? = null,
        var note: Boolean = false,
    )

    private const val ROOT_ID = "root"
    private const val DEFAULT_NESTED_DIRECTION = "TB"
}

internal data class StateRenderData(
    val direction: String,
    val nodes: Map<String, StateRenderNode>,
    val groups: List<StateRenderGroup>,
    val edges: List<StateRenderEdge>,
)

internal data class StateRenderNode(
    val id: String,
    val type: StateNodeType,
    val labels: List<String>,
    val parentId: String?,
    val styles: List<String>,
    val position: String?,
    val noteOwner: String?,
)

internal data class StateRenderGroup(
    val id: String,
    val label: String,
    val parentId: String?,
    val direction: String?,
    val divider: Boolean,
    val note: Boolean,
    val alternate: Boolean,
    val styles: List<String>,
    val colorIndex: Int?,
    val position: String?,
)

internal data class StateRenderEdge(
    val id: String,
    val start: String,
    val end: String,
    val label: String? = null,
    val pattern: SceneStrokePattern = SceneStrokePattern.Solid,
    val arrow: Boolean = true,
    val note: Boolean = false,
)
