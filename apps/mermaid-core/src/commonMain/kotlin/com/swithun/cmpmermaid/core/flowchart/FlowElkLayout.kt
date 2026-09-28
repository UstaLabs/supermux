package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidElkOptions
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.flowchart.upstream.elk.ElkJsRuntime
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidMarkerPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Kotlin translation of Mermaid 12.0.0's Flowchart ELK adapter.
 *
 * Mermaid owns graph construction, hierarchy policy, result mapping and edge
 * clipping here. [ElkJsRuntime] currently rejects execution until the elkjs
 * algorithm itself has a pure Kotlin translation.
 */
internal object FlowElkLayout {
    fun layout(
        document: FlowchartDocument,
        nodeSizes: Map<String, SceneSize>,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        edgeLabelSizes: Map<Int, SceneSize>,
        subgraphLabelSizes: Map<String, SceneSize>,
        options: MermaidRenderOptions,
    ): GMResult<FlowLayoutPlacement, MermaidError> {
        val algorithm = algorithmFor(options.layout)
            ?: return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "${options.layout} layout",
                    message = "Mermaid 12 does not register '${options.layout}' as a Flowchart ELK layout",
                ),
            )
        val hierarchy = FlowHierarchy(document)
        val input = buildInputGraph(
            document = document,
            hierarchy = hierarchy,
            nodeSizes = nodeSizes,
            edgeLabelSizes = edgeLabelSizes,
            subgraphLabelSizes = subgraphLabelSizes,
            algorithm = algorithm,
            elkOptions = options.elk,
        )
        val output = when (val result = ElkJsRuntime.layout(input.graph.toString())) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val graph = try {
            Json.parseToJsonElement(output) as? JsonObject
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "elkjs returned invalid JSON"),
            )
        } ?: return GMResult.Err(MermaidError.Layout("elkjs did not return a graph object"))

        val nodeBounds = linkedMapOf<String, SceneRect>()
        val subgraphBounds = linkedMapOf<String, SceneRect>()
        val nodeStates = linkedMapOf<String, ElkNodeState>()
        when (
            val collected = collectNodePositions(
                nodes = graph.array("children"),
                parentX = 0f,
                parentY = 0f,
                document = document,
                nodeBounds = nodeBounds,
                subgraphBounds = subgraphBounds,
                nodeStates = nodeStates,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return collected
        }
        if (nodeBounds.size != document.nodes.size) {
            val missing = document.nodes.keys - nodeBounds.keys
            return GMResult.Err(
                MermaidError.Layout(
                    "elkjs did not position Flowchart nodes: ${missing.sorted().joinToString()}",
                ),
            )
        }
        evenGroupFrames(
            elkNodes = graph.array("children"),
            graph = graph,
            document = document,
            hierarchy = hierarchy,
            nodeStates = nodeStates,
            subgraphBounds = subgraphBounds,
            subgraphLabelSizes = subgraphLabelSizes,
        )
        alignDegenerateNodesToAnchors(
            edgeObjects = graph.array("edges"),
            hierarchy = hierarchy,
            nodeStates = nodeStates,
            nodeBounds = nodeBounds,
            subgraphBounds = subgraphBounds,
        )

        val edges = when (
            val routed = collectEdges(
                edgeObjects = graph.array("edges"),
                document = document,
                hierarchy = hierarchy,
                nodeStates = nodeStates,
                nodeShapeLayouts = nodeShapeLayouts,
                inputEdgeIndices = input.edgeIndices,
            )
        ) {
            is GMResult.Ok -> routed.value
            is GMResult.Err -> return routed
        }
        return GMResult.Ok(
            FlowLayoutPlacement(
                nodeBounds = nodeBounds,
                subgraphBounds = subgraphBounds,
                edges = if (options.elk.straightenEdges) {
                    straightenEdgeTerminals(edges)
                } else {
                    edges
                },
            ),
        )
    }

    private fun buildInputGraph(
        document: FlowchartDocument,
        hierarchy: FlowHierarchy,
        nodeSizes: Map<String, SceneSize>,
        edgeLabelSizes: Map<Int, SceneSize>,
        subgraphLabelSizes: Map<String, SceneSize>,
        algorithm: String,
        elkOptions: MermaidElkOptions,
    ): ElkInput {
        val nodeDb = linkedMapOf<String, ElkInputNode>()
        val orderedIds = document.subgraphs.map(FlowSubgraph::id) + document.nodes.keys
        val subgraphsById = document.subgraphs.associateBy(FlowSubgraph::id)

        fun createNode(id: String): ElkInputNode {
            val subgraph = subgraphsById[id]
            val inputNode = if (subgraph == null) {
                val size = nodeSizes[id] ?: SceneSize(0f, 0f)
                ElkInputNode(
                    id = id,
                    parentId = hierarchy.parentById[id],
                    width = size.width,
                    height = size.height,
                    metadata = document.nodes[id]?.metadata.orEmpty(),
                )
            } else {
                val labelSize = subgraphLabelSizes[id] ?: SceneSize(0f, 0f)
                ElkInputNode(
                    id = id,
                    parentId = hierarchy.parentById[id],
                    isGroup = true,
                    label = subgraph.label,
                    labelSize = labelSize,
                    metadata = subgraph.metadata,
                    layoutOptions = buildSubgraphLayoutOptions(
                        subgraph = subgraph,
                        labelSize = labelSize,
                        algorithm = algorithm,
                        elkOptions = elkOptions,
                    ),
                )
            }
            nodeDb[id] = inputNode
            if (subgraph != null) {
                orderedIds
                    .filter { childId -> hierarchy.parentById[childId] == id }
                    .forEach { childId -> inputNode.children += createNode(childId) }
            }
            return inputNode
        }

        val rootChildren = orderedIds
            .filter { id -> hierarchy.parentById[id] == null }
            .map(::createNode)
            .toMutableList()
        configureCrossHierarchyEdges(document, hierarchy, nodeDb)
        if (elkOptions.keepEntryNodeOnTop) {
            findCyclicEntryNodes(document).forEach { id ->
                nodeDb[id]?.layoutOptions?.set(
                    "elk.layered.layering.layerConstraint",
                    JsonPrimitive("FIRST"),
                )
            }
        }

        val edgeIndices = linkedMapOf<String, MutableList<Int>>()
        val edgeObjects = document.edges.mapIndexed { index, edge ->
            edgeIndices.getOrPut(edge.id) { mutableListOf() } += index
            val label = edgeLabelSizes[index] ?: SceneSize(0f, 0f)
            buildJsonObject {
                put("id", edge.id)
                put("cmpMermaidIndex", index)
                put("start", edge.from)
                put("end", edge.to)
                put("sourceId", edge.from)
                put("targetId", edge.to)
                put("sources", buildJsonArray { add(JsonPrimitive(edge.from)) })
                put("targets", buildJsonArray { add(JsonPrimitive(edge.to)) })
                put("minlen", edge.minimumLength)
                put(
                    "labels",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("width", label.width)
                                put("height", label.height)
                                put("orgWidth", label.width)
                                put("orgHeight", label.height)
                                put("text", edge.label.orEmpty())
                                put(
                                    "layoutOptions",
                                    buildJsonObject {
                                        put("edgeLabels.inline", "true")
                                        put("edgeLabels.placement", "CENTER")
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
        val rootOptions = rootLayoutOptions(algorithm, document.direction, elkOptions)
        val graph = buildJsonObject {
            put("id", "root")
            put("layoutOptions", rootOptions.toJsonObject())
            put(
                "children",
                buildJsonArray {
                    rootChildren.forEach { child -> add(child.toJson()) }
                },
            )
            put(
                "edges",
                buildJsonArray {
                    edgeObjects.forEach(::add)
                },
            )
        }
        return ElkInput(graph, edgeIndices)
    }

    private fun rootLayoutOptions(
        algorithm: String,
        direction: FlowDirection,
        elkOptions: MermaidElkOptions,
    ): MutableMap<String, JsonElement> {
        val preset = resolvePreset(elkOptions.preset)
        return linkedMapOf<String, JsonElement>(
            "elk.hierarchyHandling" to JsonPrimitive("INCLUDE_CHILDREN"),
            "elk.algorithm" to JsonPrimitive(algorithm),
            "elk.layered.nodePlacement.strategy" to
                JsonPrimitive(elkOptions.nodePlacementStrategy ?: preset.placement),
            "elk.layered.nodePlacement.bk.fixedAlignment" to
                JsonPrimitive(elkOptions.nodePlacementAlignment ?: preset.alignment),
            "elk.layered.mergeEdges" to JsonPrimitive(elkOptions.mergeEdges),
            "elk.direction" to JsonPrimitive(direction.toElkDirection()),
            "spacing.baseValue" to JsonPrimitive(40),
            "elk.layered.crossingMinimization.forceNodeModelOrder" to
                JsonPrimitive(elkOptions.forceNodeModelOrder),
            "elk.layered.considerModelOrder.strategy" to
                JsonPrimitive(elkOptions.considerModelOrder),
            "elk.layered.unnecessaryBendpoints" to JsonPrimitive(true),
            "elk.layered.cycleBreaking.strategy" to
                JsonPrimitive(elkOptions.cycleBreakingStrategy ?: preset.cycleBreaking),
            "elk.layered.layering.strategy" to
                JsonPrimitive(elkOptions.layeringStrategy ?: preset.layering),
            "elk.layered.layering.coffmanGraham.layerBound" to
                JsonPrimitive(elkOptions.layeringLayerBound),
            "elk.layered.wrapping.multiEdge.improveCuts" to JsonPrimitive(true),
            "elk.layered.wrapping.multiEdge.improveWrappedEdges" to JsonPrimitive(true),
            "elk.layered.edgeRouting.selfLoopDistribution" to JsonPrimitive("EQUALLY"),
            "elk.layered.mergeHierarchyEdges" to JsonPrimitive(true),
            "elk.spacing.portsSurrounding" to JsonPrimitive(PORTS_SURROUNDING),
        ).also { layoutOptions ->
            if (algorithm == "elk.rectpacking") {
                layoutOptions.putAll(rectPackingOptions())
                layoutOptions["elk.contentAlignment"] = JsonPrimitive("H_CENTER V_TOP")
                layoutOptions["elk.padding"] =
                    JsonPrimitive("[top=15,left=15,bottom=15,right=15]")
            }
        }
    }

    private fun buildSubgraphLayoutOptions(
        subgraph: FlowSubgraph,
        labelSize: SceneSize,
        algorithm: String,
        elkOptions: MermaidElkOptions,
    ): MutableMap<String, JsonElement> {
        val preset = resolvePreset(elkOptions.preset)
        val options = linkedMapOf<String, JsonElement>(
            "nodeSize.constraints" to JsonPrimitive("[MINIMUM_SIZE, NODE_LABELS]"),
            "nodeSize.minimum" to JsonPrimitive("(${labelSize.width + subgraph.padding}, 0)"),
            "spacing.baseValue" to JsonPrimitive(DEFAULT_SUBGRAPH_SPACING_BASE_VALUE),
            "elk.layered.spacing.edgeNodeBetweenLayers" to JsonPrimitive(30),
            "elk.spacing.edgeEdge" to JsonPrimitive(20),
            "spacing.nodeNode" to JsonPrimitive(DEFAULT_SUBGRAPH_NODE_SPACING),
            "elk.padding" to JsonPrimitive(
                "[top=$SUBGRAPH_PADDING,left=$SUBGRAPH_PADDING," +
                    "bottom=$SUBGRAPH_PADDING,right=$SUBGRAPH_PADDING]",
            ),
            "nodeLabels.placement" to JsonPrimitive("[H_CENTER V_TOP, INSIDE]"),
            "elk.layered.mergeEdges" to JsonPrimitive(elkOptions.mergeEdges),
            "elk.layered.nodePlacement.bk.fixedAlignment" to
                JsonPrimitive(elkOptions.nodePlacementAlignment ?: preset.alignment),
            "elk.layered.nodePlacement.strategy" to JsonPrimitive(
                elkOptions.nodePlacementStrategy ?: preset.containerPlacement,
            ),
            "elk.layered.cycleBreaking.strategy" to JsonPrimitive(
                elkOptions.cycleBreakingStrategy ?: preset.cycleBreaking,
            ),
            "elk.layered.nodePlacement.networkSimplex.nodeFlexibility" to
                JsonPrimitive("PORT_POSITION"),
            "elk.spacing.portsSurrounding" to JsonPrimitive(PORTS_SURROUNDING),
        )
        val requestedAlgorithm = subgraph.metadata["algorithm"]
            ?.takeIf(CONTAINER_ALGORITHMS::contains)
        if (requestedAlgorithm != null) {
            val top = labelSize.height + CONTAINER_PADDING
            options["nodeSize.minimum"] = JsonPrimitive(
                "(${labelSize.width + subgraph.padding * 2f}, ${top + CONTAINER_PADDING})",
            )
            options["elk.algorithm"] = JsonPrimitive(requestedAlgorithm)
            options["elk.hierarchyHandling"] = JsonPrimitive("SEPARATE_CHILDREN")
            options["elk.aspectRatio"] = JsonPrimitive("2.0")
            options["elk.contentAlignment"] = JsonPrimitive("H_CENTER V_TOP")
            options["elk.expandNodes"] = JsonPrimitive("true")
            options["elk.padding"] = JsonPrimitive(
                "[top=$top,left=$CONTAINER_PADDING,bottom=$CONTAINER_PADDING," +
                    "right=$CONTAINER_PADDING]",
            )
            if (requestedAlgorithm == "elk.rectpacking") {
                val rectTop = labelSize.height + RECTPACKING_CONTAINER_PADDING
                options.putAll(rectPackingOptions())
                options["elk.padding"] = JsonPrimitive(
                    "[top=$rectTop,left=$RECTPACKING_CONTAINER_PADDING," +
                        "bottom=$RECTPACKING_CONTAINER_PADDING," +
                        "right=$RECTPACKING_CONTAINER_PADDING]",
                )
                options["nodeSize.minimum"] = JsonPrimitive(
                    "(${labelSize.width + subgraph.padding * 2f}, " +
                        "${rectTop + RECTPACKING_CONTAINER_PADDING})",
                )
            }
        } else if (subgraph.direction != null) {
            options["elk.algorithm"] = JsonPrimitive(algorithm)
            options["elk.direction"] = JsonPrimitive(subgraph.direction.toElkDirection())
            options["elk.hierarchyHandling"] = JsonPrimitive("SEPARATE_CHILDREN")
        }
        return options
    }

    private fun rectPackingOptions(): Map<String, JsonElement> = linkedMapOf(
        "spacing.baseValue" to JsonPrimitive(15),
        "spacing.nodeNode" to JsonPrimitive(15),
        "elk.aspectRatio" to JsonPrimitive("1.6"),
        "elk.expandNodes" to JsonPrimitive("true"),
        "elk.rectpacking.trybox" to JsonPrimitive("true"),
        "elk.rectpacking.packing.compaction.rowHeightReevaluation" to JsonPrimitive("true"),
        "elk.rectpacking.packing.compaction.iterations" to JsonPrimitive(10),
        "elk.rectpacking.whiteSpaceElimination.strategy" to
            JsonPrimitive("EQUAL_BETWEEN_STRUCTURES"),
        "elk.rectpacking.widthApproximation.strategy" to JsonPrimitive("SCANLINE"),
    )

    private fun configureCrossHierarchyEdges(
        document: FlowchartDocument,
        hierarchy: FlowHierarchy,
        nodeDb: Map<String, ElkInputNode>,
    ) {
        document.edges.forEach { edge ->
            val source = nodeDb[edge.from] ?: return@forEach
            val target = nodeDb[edge.to] ?: return@forEach
            if (source.parentId == target.parentId) {
                return@forEach
            }
            val ancestor = hierarchy.findCommonAncestor(edge.from, edge.to)
            setIncludeChildrenPolicy(source.id, ancestor, hierarchy, nodeDb)
            setIncludeChildrenPolicy(target.id, ancestor, hierarchy, nodeDb)
        }
    }

    private fun setIncludeChildrenPolicy(
        nodeId: String,
        ancestorId: String,
        hierarchy: FlowHierarchy,
        nodeDb: Map<String, ElkInputNode>,
    ) {
        val node = nodeDb[nodeId] ?: return
        if (
            node.layoutOptions["elk.hierarchyHandling"]?.primitiveContent() ==
            "SEPARATE_CHILDREN" &&
            node.metadata["algorithm"] in CONTAINER_ALGORITHMS
        ) {
            CONTAINER_ALGORITHM_OVERRIDES.forEach(node.layoutOptions::remove)
            node.layoutOptions["spacing.baseValue"] =
                JsonPrimitive(DEFAULT_SUBGRAPH_SPACING_BASE_VALUE)
            node.layoutOptions["spacing.nodeNode"] =
                JsonPrimitive(DEFAULT_SUBGRAPH_NODE_SPACING)
            if (node.labelSize != null) {
                node.layoutOptions["nodeSize.constraints"] =
                    JsonPrimitive("[MINIMUM_SIZE, NODE_LABELS]")
                node.layoutOptions["nodeSize.minimum"] =
                    JsonPrimitive("(${node.labelSize.width + FLOW_GROUP_PADDING}, 0)")
            }
        }
        node.layoutOptions["elk.hierarchyHandling"] = JsonPrimitive("INCLUDE_CHILDREN")
        val parentId = hierarchy.parentById[node.id]
        if (node.id != ancestorId && parentId != null) {
            setIncludeChildrenPolicy(parentId, ancestorId, hierarchy, nodeDb)
        }
    }

    internal fun findCyclicEntryNodes(document: FlowchartDocument): Set<String> {
        val hierarchy = FlowHierarchy(document)
        val orderedIds = document.subgraphs.map(FlowSubgraph::id) + document.nodes.keys
        val groups = orderedIds.groupBy { id -> hierarchy.parentById[id] }
        val entries = linkedSetOf<String>()

        groups.values.forEach { ids ->
            val idSet = ids.toSet()
            val inDegree = ids.associateWithTo(linkedMapOf()) { 0 }
            val neighbors = ids.associateWithTo(linkedMapOf()) { mutableListOf<String>() }
            val internalEdges = mutableListOf<Pair<String, String>>()
            document.edges.forEach { edge ->
                val source = edge.from
                val target = edge.to
                if (source == target || source !in idSet || target !in idSet) {
                    return@forEach
                }
                inDegree[target] = inDegree.getValue(target) + 1
                neighbors.getValue(source) += target
                neighbors.getValue(target) += source
                internalEdges += source to target
            }

            val component = linkedMapOf<String, Int>()
            var componentCount = 0
            ids.forEach { id ->
                if (id in component) {
                    return@forEach
                }
                val stack = mutableListOf(id)
                component[id] = componentCount
                while (stack.isNotEmpty()) {
                    val current = stack.removeAt(stack.lastIndex)
                    neighbors.getValue(current).forEach { next ->
                        if (next !in component) {
                            component[next] = componentCount
                            stack += next
                        }
                    }
                }
                componentCount++
            }

            val hasSource = MutableList(componentCount) { false }
            ids.forEach { id ->
                if (inDegree.getValue(id) == 0) {
                    hasSource[component.getValue(id)] = true
                }
            }
            if (hasSource.none { value -> !value }) {
                return@forEach
            }

            val forward = ids.associateWithTo(linkedMapOf()) { mutableListOf<String>() }
            val residualInDegree = ids.associateWithTo(linkedMapOf()) { 0 }
            fun reaches(from: String, to: String): Boolean {
                val seen = mutableSetOf(from)
                val stack = mutableListOf(from)
                while (stack.isNotEmpty()) {
                    val current = stack.removeAt(stack.lastIndex)
                    if (current == to) {
                        return true
                    }
                    forward.getValue(current).forEach { next ->
                        if (seen.add(next)) {
                            stack += next
                        }
                    }
                }
                return false
            }
            internalEdges.forEach { (source, target) ->
                if (!reaches(target, source)) {
                    forward.getValue(source) += target
                    residualInDegree[target] = residualInDegree.getValue(target) + 1
                }
            }

            val nominated = MutableList(componentCount) { false }
            ids.forEach { id ->
                val componentIndex = component.getValue(id)
                if (
                    !hasSource[componentIndex] &&
                    !nominated[componentIndex] &&
                    residualInDegree.getValue(id) == 0
                ) {
                    entries += id
                    nominated[componentIndex] = true
                }
            }
        }
        return entries
    }

    private fun collectNodePositions(
        nodes: JsonArray,
        parentX: Float,
        parentY: Float,
        document: FlowchartDocument,
        nodeBounds: MutableMap<String, SceneRect>,
        subgraphBounds: MutableMap<String, SceneRect>,
        nodeStates: MutableMap<String, ElkNodeState>,
    ): GMResult<Unit, MermaidError> {
        nodes.forEach { element ->
            val node = element as? JsonObject
                ?: return GMResult.Err(MermaidError.Layout("elkjs returned a non-object node"))
            val id = node.string("id")
                ?: return GMResult.Err(MermaidError.Layout("elkjs returned a node without an id"))
            val x = node.float("x")
                ?: return missingNodeNumber(id, "x")
            val y = node.float("y")
                ?: return missingNodeNumber(id, "y")
            val width = node.float("width")
                ?: return missingNodeNumber(id, "width")
            val height = node.float("height")
                ?: return missingNodeNumber(id, "height")
            val left = parentX + x
            val top = parentY + y
            val bounds = SceneRect(left, top, left + width, top + height)
            val isGroup = document.subgraphs.any { subgraph -> subgraph.id == id }
            nodeStates[id] = ElkNodeState(
                id = id,
                bounds = bounds,
                elkBounds = bounds,
                isGroup = isGroup,
            )
            if (id in document.nodes) {
                nodeBounds[id] = bounds
            }
            if (isGroup) {
                subgraphBounds[id] = bounds
            }
            when (
                val children = collectNodePositions(
                    nodes = node.array("children"),
                    parentX = left,
                    parentY = top,
                    document = document,
                    nodeBounds = nodeBounds,
                    subgraphBounds = subgraphBounds,
                    nodeStates = nodeStates,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return children
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun evenGroupFrames(
        elkNodes: JsonArray,
        graph: JsonObject,
        document: FlowchartDocument,
        hierarchy: FlowHierarchy,
        nodeStates: MutableMap<String, ElkNodeState>,
        subgraphBounds: MutableMap<String, SceneRect>,
        subgraphLabelSizes: Map<String, SceneSize>,
    ) {
        val subgraphsById = document.subgraphs.associateBy(FlowSubgraph::id)
        fun visit(nodes: JsonArray) {
            nodes.forEach { element ->
                val elkNode = element as? JsonObject ?: return@forEach
                val id = elkNode.string("id") ?: return@forEach
                val subgraph = subgraphsById[id] ?: return@forEach
                val children = elkNode.array("children")
                visit(children)

                val childBounds = children.mapNotNull { child ->
                    val childId = (child as? JsonObject)?.string("id")
                    childId?.let(nodeStates::get)?.bounds
                }.filter { bounds -> bounds.width > 0f && bounds.height > 0f }
                val group = nodeStates[id]
                if (group == null || childBounds.isEmpty()) {
                    return@forEach
                }

                val lane = internalEdgePoints(
                    graph = graph,
                    descendants = collectDescendantIds(elkNode),
                    hierarchy = hierarchy,
                    nodeStates = nodeStates,
                    groupId = id,
                )
                val firstChild = childBounds.firstOrNull() ?: return@forEach
                var contentLeft = firstChild.left
                var contentRight = firstChild.right
                var contentBottom = firstChild.bottom
                childBounds.drop(1).forEach { bounds ->
                    contentLeft = min(contentLeft, bounds.left)
                    contentRight = max(contentRight, bounds.right)
                    contentBottom = max(contentBottom, bounds.bottom)
                }
                lane.forEach { point ->
                    contentLeft = min(contentLeft, point.x)
                    contentRight = max(contentRight, point.x)
                    contentBottom = max(contentBottom, point.y)
                }
                val origin = group.elkBounds
                val left = max(origin.left, contentLeft - SUBGRAPH_PADDING)
                val right = min(origin.right, contentRight + SUBGRAPH_PADDING)
                val bottom = min(origin.bottom, contentBottom + SUBGRAPH_PADDING)
                val top = origin.top
                val labelFloor =
                    (subgraphLabelSizes[id]?.width ?: 0f) + subgraph.padding
                var x = left
                var width = right - left
                if (width < labelFloor) {
                    x -= (labelFloor - width) / 2f
                    width = labelFloor
                    x = max(origin.left, min(x, origin.right - width))
                    width = min(width, origin.width)
                }
                val height = bottom - top
                if (height <= 0f || width <= 0f) {
                    return@forEach
                }
                val adjusted = SceneRect(
                    left = x,
                    top = top,
                    right = x + width,
                    bottom = top + height,
                )
                group.bounds = adjusted
                subgraphBounds[id] = adjusted
            }
        }
        visit(elkNodes)
    }

    private fun collectDescendantIds(
        elkNode: JsonObject,
        into: MutableSet<String> = linkedSetOf(),
    ): Set<String> {
        elkNode.array("children").forEach { element ->
            val child = element as? JsonObject ?: return@forEach
            child.string("id")?.let(into::add)
            collectDescendantIds(child, into)
        }
        return into
    }

    private fun internalEdgePoints(
        graph: JsonObject,
        descendants: Set<String>,
        hierarchy: FlowHierarchy,
        nodeStates: Map<String, ElkNodeState>,
        groupId: String,
    ): List<ScenePoint> = buildList {
        graph.array("edges").forEach { element ->
            val edge = element as? JsonObject ?: return@forEach
            val source = edge.firstString("sources") ?: edge.string("start") ?: return@forEach
            val target = edge.firstString("targets") ?: edge.string("end") ?: return@forEach
            val isInternal = source in descendants && target in descendants
            val attachesAtStart = source == groupId
            val attachesAtEnd = target == groupId
            if (!isInternal && !attachesAtStart && !attachesAtEnd) {
                return@forEach
            }
            val offset = elkCoordinateOffset(source, target, hierarchy, nodeStates)
            edge.array("sections").forEach { sectionElement ->
                val section = sectionElement as? JsonObject ?: return@forEach
                if (isInternal || attachesAtStart) {
                    section.point("startPoint", offset)?.let(::add)
                }
                if (isInternal) {
                    section.array("bendPoints").forEach { bend ->
                        (bend as? JsonObject)?.toPoint(offset)?.let(::add)
                    }
                }
                if (isInternal || attachesAtEnd) {
                    section.point("endPoint", offset)?.let(::add)
                }
            }
        }
    }

    private fun alignDegenerateNodesToAnchors(
        edgeObjects: JsonArray,
        hierarchy: FlowHierarchy,
        nodeStates: MutableMap<String, ElkNodeState>,
        nodeBounds: MutableMap<String, SceneRect>,
        subgraphBounds: MutableMap<String, SceneRect>,
    ) {
        val alignedNodes = mutableSetOf<String>()
        edgeObjects.forEach { element ->
            val edge = element as? JsonObject ?: return@forEach
            val section = edge.array("sections").firstOrNull() as? JsonObject ?: return@forEach
            val source = edge.firstString("sources") ?: edge.string("start") ?: return@forEach
            val target = edge.firstString("targets") ?: edge.string("end") ?: return@forEach
            val offset = elkCoordinateOffset(source, target, hierarchy, nodeStates)
            section.point("startPoint", offset)?.let { anchor ->
                alignDegenerateNodeToAnchor(
                    state = nodeStates[source] ?: return@let,
                    anchor = anchor,
                    alignedNodes = alignedNodes,
                    nodeBounds = nodeBounds,
                    subgraphBounds = subgraphBounds,
                )
            }
            section.point("endPoint", offset)?.let { anchor ->
                alignDegenerateNodeToAnchor(
                    state = nodeStates[target] ?: return@let,
                    anchor = anchor,
                    alignedNodes = alignedNodes,
                    nodeBounds = nodeBounds,
                    subgraphBounds = subgraphBounds,
                )
            }
        }
    }

    private fun alignDegenerateNodeToAnchor(
        state: ElkNodeState,
        anchor: ScenePoint,
        alignedNodes: MutableSet<String>,
        nodeBounds: MutableMap<String, SceneRect>,
        subgraphBounds: MutableMap<String, SceneRect>,
    ) {
        val bounds = state.bounds
        val alongWidth =
            abs(anchor.y - bounds.top) <= DEGENERATE_ALIGNMENT_TOLERANCE ||
                abs(anchor.y - bounds.bottom) <= DEGENERATE_ALIGNMENT_TOLERANCE
        if (
            (if (alongWidth) bounds.width else bounds.height) >=
            2f * PORTS_SURROUNDING_MARGIN ||
            !alignedNodes.add(state.id)
        ) {
            return
        }
        val delta = if (alongWidth) {
            anchor.x - bounds.center.x
        } else {
            anchor.y - bounds.center.y
        }
        if (abs(delta) < JOG_EPSILON) {
            return
        }
        val adjusted = if (alongWidth) {
            bounds.translate(delta, 0f)
        } else {
            bounds.translate(0f, delta)
        }
        state.bounds = adjusted
        if (state.id in nodeBounds) {
            nodeBounds[state.id] = adjusted
        }
        if (state.id in subgraphBounds) {
            subgraphBounds[state.id] = adjusted
        }
    }

    private fun missingNodeNumber(
        id: String,
        property: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Layout("elkjs node '$id' has no numeric '$property'"),
    )

    private fun collectEdges(
        edgeObjects: JsonArray,
        document: FlowchartDocument,
        hierarchy: FlowHierarchy,
        nodeStates: Map<String, ElkNodeState>,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        inputEdgeIndices: Map<String, List<Int>>,
    ): GMResult<Map<Int, FlowRoutedEdge>, MermaidError> {
        val result = linkedMapOf<Int, FlowRoutedEdge>()
        val nextOccurrence = linkedMapOf<String, Int>()
        edgeObjects.forEach { element ->
            val edgeObject = element as? JsonObject
                ?: return GMResult.Err(MermaidError.Layout("elkjs returned a non-object edge"))
            val edgeId = edgeObject.string("id")
                ?: return GMResult.Err(MermaidError.Layout("elkjs returned an edge without an id"))
            val index = edgeObject.int("cmpMermaidIndex")
                ?: inputEdgeIndices[edgeId]?.let { indices ->
                    val occurrence = nextOccurrence.getOrElse(edgeId) { 0 }
                    nextOccurrence[edgeId] = occurrence + 1
                    indices.getOrNull(occurrence)
                }
                ?: return GMResult.Err(
                    MermaidError.Layout("Cannot map elkjs edge '$edgeId' to Flowchart data"),
                )
            val edge = document.edges.getOrNull(index)
                ?: return GMResult.Err(
                    MermaidError.Layout("elkjs edge '$edgeId' has invalid source index $index"),
                )
            val startState = nodeStates[edge.from]
                ?: return GMResult.Err(
                    MermaidError.Layout("elkjs edge '$edgeId' has no source node '${edge.from}'"),
                )
            val endState = nodeStates[edge.to]
                ?: return GMResult.Err(
                    MermaidError.Layout("elkjs edge '$edgeId' has no target node '${edge.to}'"),
                )
            val offset = elkCoordinateOffset(
                source = edge.from,
                target = edge.to,
                hierarchy = hierarchy,
                nodeStates = nodeStates,
            )
            val section = edgeObject.array("sections").firstOrNull() as? JsonObject
            val curveOverride: String?
            val rawPoints = if (section == null) {
                curveOverride = "linear"
                listOf(startState.bounds.center, endState.bounds.center)
            } else {
                curveOverride = "rounded"
                when (val points = sectionPoints(section, offset, edgeId)) {
                    is GMResult.Ok -> points.value
                    is GMResult.Err -> return points
                }
            }
            val clippedPoints = sanitizeEdgePoints(
                originalPoints = rawPoints,
                start = endpoint(
                    id = edge.from,
                    state = startState,
                    document = document,
                    nodeShapeLayouts = nodeShapeLayouts,
                ),
                end = endpoint(
                    id = edge.to,
                    state = endState,
                    document = document,
                    nodeShapeLayouts = nodeShapeLayouts,
                ),
            )
            val points = if (section == null) {
                clippedPoints
            } else {
                ensureStartMarkerSegmentLength(
                    points = ensureEndMarkerSegmentLength(
                        points = clippedPoints,
                        endBounds = endState.bounds,
                        markerOffset = MermaidMarkerPort.terminalSegmentOffset(edge.arrowEnd),
                    ),
                    startBounds = startState.bounds,
                    markerOffset = MermaidMarkerPort.terminalSegmentOffset(edge.arrowStart),
                )
            }
            if (points.size < 2) {
                return GMResult.Err(
                    MermaidError.Layout("elkjs edge '$edgeId' produced fewer than two points"),
                )
            }
            val label = edgeObject.array("labels").firstOrNull() as? JsonObject
            val labelAnchor = if (label != null) {
                val x = label.float("x")
                val y = label.float("y")
                val width = label.float("width")
                val height = label.float("height")
                if (x != null && y != null && width != null && height != null) {
                    ScenePoint(
                        x = x + offset.x + width / 2f,
                        y = y + offset.y + height / 2f,
                    )
                } else {
                    points.halfLengthPoint()
                }
            } else {
                points.halfLengthPoint()
            }
            result[index] = FlowRoutedEdge(
                points = points,
                labelAnchor = labelAnchor,
                curveOverride = curveOverride,
            )
        }
        return GMResult.Ok(result)
    }

    private fun elkCoordinateOffset(
        source: String,
        target: String,
        hierarchy: FlowHierarchy,
        nodeStates: Map<String, ElkNodeState>,
    ): ScenePoint = hierarchy.findCommonAncestor(source, target)
        .takeUnless { ancestor -> ancestor == ROOT_ID }
        ?.let(nodeStates::get)
        ?.elkBounds
        ?.let { bounds -> ScenePoint(bounds.left, bounds.top) }
        ?: ScenePoint(0f, 0f)

    private fun sectionPoints(
        section: JsonObject,
        offset: ScenePoint,
        edgeId: String,
    ): GMResult<List<ScenePoint>, MermaidError> {
        val start = section.point("startPoint", offset)
            ?: return GMResult.Err(
                MermaidError.Layout("elkjs edge '$edgeId' section has no startPoint"),
            )
        val end = section.point("endPoint", offset)
            ?: return GMResult.Err(
                MermaidError.Layout("elkjs edge '$edgeId' section has no endPoint"),
            )
        val bends = section.array("bendPoints").mapNotNull { element ->
            (element as? JsonObject)?.toPoint(offset)
        }
        return GMResult.Ok(listOf(start) + bends + end)
    }

    private fun endpoint(
        id: String,
        state: ElkNodeState,
        document: FlowchartDocument,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
    ): ElkEndpoint {
        val node = document.nodes[id]
        return ElkEndpoint(
            bounds = state.bounds,
            shapeLayout = nodeShapeLayouts[id],
            isGroup = state.isGroup,
            isRectangle = node?.shape == SceneShapeKind.Rectangle,
        )
    }

    private fun sanitizeEdgePoints(
        originalPoints: List<ScenePoint>,
        start: ElkEndpoint,
        end: ElkEndpoint,
    ): List<ScenePoint> {
        if (originalPoints.isEmpty()) {
            return emptyList()
        }
        val points = originalPoints.toMutableList()
        if (!start.isGroup && !start.isRectangle) {
            points.add(0, start.bounds.center)
        }
        if (!end.isGroup && !end.isRectangle) {
            points += end.bounds.center
        }

        if (start.isGroup) {
            if (!onBorder(start.bounds, candidatePoint(points, start.bounds.center, true))) {
                clipGroupEndpoint(points, start.bounds, fromStart = true)
            }
        } else {
            applyNodeIntersection(points, start, fromStart = true)
        }
        if (end.isGroup) {
            if (!onBorder(end.bounds, candidatePoint(points, end.bounds.center, false))) {
                clipGroupEndpoint(points, end.bounds, fromStart = false)
            }
        } else {
            applyNodeIntersection(points, end, fromStart = false)
        }

        if (points.size > 1 && points[points.lastIndex].distanceTo(points[points.lastIndex - 1]) < 2f) {
            points.removeAt(points.lastIndex - 1)
        }
        return points.filterIndexed { index, point ->
            index == 0 || point.distanceTo(points[index - 1]) > POINT_EPSILON
        }
    }

    private fun ensureStartMarkerSegmentLength(
        points: List<ScenePoint>,
        startBounds: SceneRect,
        markerOffset: Float,
    ): List<ScenePoint> {
        if (markerOffset <= 0f || points.size < 3) {
            return points
        }
        val start = points[0]
        val exit = points[1]
        val segmentLength = start.distanceTo(exit)
        if (
            segmentLength >= max(MIN_END_MARKER_SEGMENT_LENGTH, markerOffset * 2f) ||
            !onBorder(startBounds, exit, MARKER_BORDER_TOLERANCE)
        ) {
            return points
        }
        return listOf(start) + points.drop(2)
    }

    private fun ensureEndMarkerSegmentLength(
        points: List<ScenePoint>,
        endBounds: SceneRect,
        markerOffset: Float,
    ): List<ScenePoint> {
        if (markerOffset <= 0f || points.size < 3) {
            return points
        }
        val end = points.last()
        val entry = points[points.lastIndex - 1]
        val segmentLength = entry.distanceTo(end)
        if (
            segmentLength >= max(MIN_END_MARKER_SEGMENT_LENGTH, markerOffset * 2f) ||
            !onBorder(endBounds, entry, MARKER_BORDER_TOLERANCE)
        ) {
            return points
        }
        return points.dropLast(2) + end
    }

    private fun candidatePoint(
        points: List<ScenePoint>,
        center: ScenePoint,
        fromStart: Boolean,
    ): ScenePoint {
        if (points.isEmpty()) {
            return center
        }
        val index = if (fromStart) 0 else points.lastIndex
        val candidate = points[index]
        if (candidate.distanceTo(center) > POINT_EPSILON || points.size == 1) {
            return candidate
        }
        return points[if (fromStart) 1 else points.lastIndex - 1]
    }

    private fun applyNodeIntersection(
        points: MutableList<ScenePoint>,
        endpoint: ElkEndpoint,
        fromStart: Boolean,
    ) {
        if (points.size < 2) {
            return
        }
        val indices = if (fromStart) points.indices else points.indices.reversed()
        val outsideIndex = indices.firstOrNull { index ->
            outsideNode(endpoint.bounds, points[index])
        } ?: if (fromStart) points.lastIndex else 0
        val outside = points[outsideIndex]
        val adjacentIndex = if (fromStart) {
            min(points.lastIndex, outsideIndex + 1)
        } else {
            max(0, outsideIndex - 1)
        }
        val intersection = outlineAttachPoint(
            endpoint = endpoint,
            port = outside,
            next = points[adjacentIndex],
        ) ?: polygonIntersection(endpoint, outside)
        if (fromStart) {
            points[0] = intersection
        } else {
            points[points.lastIndex] = intersection
        }
    }

    private fun outlineAttachPoint(
        endpoint: ElkEndpoint,
        port: ScenePoint,
        next: ScenePoint,
    ): ScenePoint? {
        val outline = endpoint.absoluteOutline() ?: return null
        val dx = next.x - port.x
        val dy = next.y - port.y
        if (abs(dx) <= DEPARTURE_AXIS_EPS && abs(dy) <= DEPARTURE_AXIS_EPS) {
            return null
        }
        if (abs(dx) > DEPARTURE_AXIS_EPS && abs(dy) > DEPARTURE_AXIS_EPS) {
            return null
        }
        val center = endpoint.bounds.center
        val horizontal = abs(dx) > abs(dy)
        fun pointAt(value: Float): ScenePoint = if (horizontal) {
            ScenePoint(value, port.y)
        } else {
            ScenePoint(port.x, value)
        }
        var inner = if (horizontal) center.x else center.y
        var outer = if (horizontal) port.x else port.y
        if (!insidePolygon(outline, pointAt(inner))) {
            return null
        }
        if (insidePolygon(outline, pointAt(outer))) {
            return port
        }
        repeat(OUTLINE_RAY_STEPS) {
            val middle = (inner + outer) / 2f
            if (insidePolygon(outline, pointAt(middle))) {
                inner = middle
            } else {
                outer = middle
            }
        }
        return pointAt(inner)
    }

    private fun polygonIntersection(
        endpoint: ElkEndpoint,
        toward: ScenePoint,
    ): ScenePoint {
        val center = endpoint.bounds.center
        val outline = endpoint.absoluteOutline() ?: endpoint.bounds.rectangleOutline()
        val directionX = toward.x - center.x
        val directionY = toward.y - center.y
        var best: Pair<Float, ScenePoint>? = null
        (outline + outline.first()).zipWithNext().forEach { (first, second) ->
            val segmentX = second.x - first.x
            val segmentY = second.y - first.y
            val denominator = directionX * segmentY - directionY * segmentX
            if (abs(denominator) < POINT_EPSILON) {
                return@forEach
            }
            val offsetX = first.x - center.x
            val offsetY = first.y - center.y
            val ray = (offsetX * segmentY - offsetY * segmentX) / denominator
            val segment = (offsetX * directionY - offsetY * directionX) / denominator
            val current = best
            if (
                ray in 0f..1f &&
                segment in 0f..1f &&
                (current == null || ray > current.first)
            ) {
                best = ray to ScenePoint(
                    x = center.x + directionX * ray,
                    y = center.y + directionY * ray,
                )
            }
        }
        return best?.second ?: center
    }

    private fun clipGroupEndpoint(
        points: MutableList<ScenePoint>,
        bounds: SceneRect,
        fromStart: Boolean,
    ): Boolean {
        val step = if (fromStart) 1 else -1
        var index = if (fromStart) 0 else points.lastIndex
        val terminalIndex = index
        while (index in points.indices && !outsideNode(bounds, points[index])) {
            index += step
        }
        if (index == terminalIndex || index !in points.indices) {
            return false
        }
        val outside = points[index]
        val inside = points[index - step]
        val dx = outside.x - inside.x
        val dy = outside.y - inside.y
        val tx = if (dx == 0f) {
            Float.POSITIVE_INFINITY
        } else {
            (bounds.center.x + sign(dx) * bounds.width / 2f - inside.x) / dx
        }
        val ty = if (dy == 0f) {
            Float.POSITIVE_INFINITY
        } else {
            (bounds.center.y + sign(dy) * bounds.height / 2f - inside.y) / dy
        }
        val ratio = min(tx, ty)
        val crossing = ScenePoint(inside.x + ratio * dx, inside.y + ratio * dy)
        if (fromStart) {
            repeat(index) { points.removeAt(0) }
            points[0] = crossing
        } else {
            while (points.lastIndex > index) {
                points.removeAt(points.lastIndex)
            }
            points[points.lastIndex] = crossing
        }
        return true
    }

    private fun straightenEdgeTerminals(
        routedEdges: Map<Int, FlowRoutedEdge>,
    ): Map<Int, FlowRoutedEdge> {
        val indices = routedEdges.keys.toList()
        val routes = indices.map { index -> routedEdges.getValue(index).points }.toMutableList()
        indices.forEachIndexed { routeIndex, edgeIndex ->
            val original = routes[routeIndex]
            if (original.size < 5) {
                return@forEachIndexed
            }
            val candidate = straightenTerminalJogs(original)
            if (candidate === original) {
                return@forEachIndexed
            }
            var before = 0
            var after = 0
            routes.forEachIndexed { otherIndex, route ->
                if (otherIndex != routeIndex && route.size >= 2) {
                    before += crossingCount(original, route)
                    after += crossingCount(candidate, route)
                }
            }
            if (after <= before) {
                routes[routeIndex] = candidate
            }
        }
        return indices.mapIndexed { routeIndex, edgeIndex ->
            edgeIndex to routedEdges.getValue(edgeIndex).copy(points = routes[routeIndex])
        }.toMap(linkedMapOf())
    }

    private fun straightenTerminalJogs(points: List<ScenePoint>): List<ScenePoint> {
        var result = straightenFront(points) ?: points
        val fixedEnd = straightenFront(result.reversed())
        if (fixedEnd != null) {
            result = fixedEnd.reversed()
        }
        return result
    }

    private fun straightenFront(points: List<ScenePoint>): List<ScenePoint>? {
        if (points.size < 5) {
            return null
        }
        val first = points[0]
        val second = points[1]
        val third = points[2]
        val fourth = points[3]
        val axis = axisOf(first, second) ?: return null
        if (
            axisOf(third, fourth) != axis ||
            axisOf(second, third) != axis.opposite()
        ) {
            return null
        }
        if (first.distanceTo(second) > TERMINAL_RUN_MAX) {
            return null
        }
        val jog = if (axis == Axis.Horizontal) {
            abs(third.y - second.y)
        } else {
            abs(third.x - second.x)
        }
        if (jog < JOG_EPSILON || jog > TERMINAL_JOG_MAX) {
            return null
        }
        val forward = if (axis == Axis.Horizontal) {
            sign(second.x - first.x) == sign(fourth.x - third.x)
        } else {
            sign(second.y - first.y) == sign(fourth.y - third.y)
        }
        if (!forward) {
            return null
        }
        var last = 3
        while (last + 1 < points.size && axisOf(points[last], points[last + 1]) == axis) {
            last++
        }
        if (last == points.lastIndex) {
            return null
        }
        val moved = points.toMutableList()
        for (index in 2..last) {
            moved[index] = if (axis == Axis.Horizontal) {
                ScenePoint(points[index].x, first.y)
            } else {
                ScenePoint(first.x, points[index].y)
            }
        }
        moved.removeAt(2)
        moved.removeAt(1)
        return moved
    }

    private fun crossingCount(
        first: List<ScenePoint>,
        second: List<ScenePoint>,
    ): Int {
        var count = 0
        for (firstIndex in 0 until first.lastIndex) {
            for (secondIndex in 0 until second.lastIndex) {
                if (
                    segmentsCrossStrict(
                        first[firstIndex],
                        first[firstIndex + 1],
                        second[secondIndex],
                        second[secondIndex + 1],
                    )
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun segmentsCrossStrict(
        firstStart: ScenePoint,
        firstEnd: ScenePoint,
        secondStart: ScenePoint,
        secondEnd: ScenePoint,
    ): Boolean {
        fun side(origin: ScenePoint, first: ScenePoint, second: ScenePoint): Float =
            (first.x - origin.x) * (second.y - origin.y) -
                (first.y - origin.y) * (second.x - origin.x)

        val d1 = side(secondStart, secondEnd, firstStart)
        val d2 = side(secondStart, secondEnd, firstEnd)
        val d3 = side(firstStart, firstEnd, secondStart)
        val d4 = side(firstStart, firstEnd, secondEnd)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun axisOf(
        first: ScenePoint,
        second: ScenePoint,
    ): Axis? {
        val dx = abs(second.x - first.x)
        val dy = abs(second.y - first.y)
        return when {
            dx > JOG_EPSILON && dy <= JOG_EPSILON -> Axis.Horizontal
            dy > JOG_EPSILON && dx <= JOG_EPSILON -> Axis.Vertical
            else -> null
        }
    }

    private fun insidePolygon(
        polygon: List<ScenePoint>,
        point: ScenePoint,
    ): Boolean {
        if (polygon.size < 3) {
            return false
        }
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if (pointOnSegment(point, previous, current)) {
                return true
            }
            val intersects = (current.y > point.y) != (previous.y > point.y) &&
                point.x <
                (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            if (intersects) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun pointOnSegment(
        point: ScenePoint,
        start: ScenePoint,
        end: ScenePoint,
    ): Boolean {
        val cross = (point.y - start.y) * (end.x - start.x) -
            (point.x - start.x) * (end.y - start.y)
        if (abs(cross) > POINT_EPSILON) {
            return false
        }
        return point.x in min(start.x, end.x) - POINT_EPSILON..
            max(start.x, end.x) + POINT_EPSILON &&
            point.y in min(start.y, end.y) - POINT_EPSILON..
            max(start.y, end.y) + POINT_EPSILON
    }

    private fun outsideNode(
        bounds: SceneRect,
        point: ScenePoint,
    ): Boolean =
        abs(point.x - bounds.center.x) >= bounds.width / 2f ||
            abs(point.y - bounds.center.y) >= bounds.height / 2f

    private fun onBorder(
        bounds: SceneRect,
        point: ScenePoint,
        tolerance: Float = 0.5f,
    ): Boolean {
        val onLeft = abs(point.x - bounds.left) <= tolerance &&
            point.y in bounds.top - tolerance..bounds.bottom + tolerance
        val onRight = abs(point.x - bounds.right) <= tolerance &&
            point.y in bounds.top - tolerance..bounds.bottom + tolerance
        val onTop = abs(point.y - bounds.top) <= tolerance &&
            point.x in bounds.left - tolerance..bounds.right + tolerance
        val onBottom = abs(point.y - bounds.bottom) <= tolerance &&
            point.x in bounds.left - tolerance..bounds.right + tolerance
        return onLeft || onRight || onTop || onBottom
    }

    private fun algorithmFor(layout: String): String? = when (layout) {
        "elk", "elk.layered" -> "elk.layered"
        "elk.stress" -> "elk.stress"
        "elk.force" -> "elk.force"
        "elk.mrtree" -> "elk.mrtree"
        "elk.sporeOverlap" -> "elk.sporeOverlap"
        "elk.box" -> "elk.box"
        "elk.rectpacking" -> "elk.rectpacking"
        else -> null
    }

    private fun FlowDirection.toElkDirection(): String = when (this) {
        FlowDirection.LeftToRight -> "RIGHT"
        FlowDirection.RightToLeft -> "LEFT"
        FlowDirection.BottomToTop -> "UP"
        FlowDirection.TopToBottom -> "DOWN"
    }

    private fun Map<String, JsonElement>.toJsonObject(): JsonObject =
        JsonObject(this)

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.firstString(name: String): String? =
        array(name).firstOrNull()?.let { element ->
            (element as? JsonPrimitive)?.content
        }

    private fun JsonObject.float(name: String): Float? =
        (this[name] as? JsonPrimitive)?.content?.toFloatOrNull()

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.point(
        name: String,
        offset: ScenePoint,
    ): ScenePoint? =
        (this[name] as? JsonObject)?.toPoint(offset)

    private fun JsonObject.toPoint(offset: ScenePoint): ScenePoint? {
        val x = float("x") ?: return null
        val y = float("y") ?: return null
        return ScenePoint(x + offset.x, y + offset.y)
    }

    private fun JsonElement.primitiveContent(): String? =
        (this as? JsonPrimitive)?.content

    private fun ScenePoint.distanceTo(other: ScenePoint): Float =
        hypot(x - other.x, y - other.y)

    private fun List<ScenePoint>.halfLengthPoint(): ScenePoint {
        if (size == 1) {
            return first()
        }
        val lengths = zipWithNext { first, second ->
            abs(second.x - first.x) + abs(second.y - first.y)
        }
        val target = lengths.sum() / 2f
        var traversed = 0f
        lengths.forEachIndexed { index, length ->
            if (traversed + length >= target && length > 0f) {
                val ratio = (target - traversed) / length
                return ScenePoint(
                    x = this[index].x + (this[index + 1].x - this[index].x) * ratio,
                    y = this[index].y + (this[index + 1].y - this[index].y) * ratio,
                )
            }
            traversed += length
        }
        return last()
    }

    private fun SceneRect.rectangleOutline(): List<ScenePoint> = listOf(
        ScenePoint(left, top),
        ScenePoint(right, top),
        ScenePoint(right, bottom),
        ScenePoint(left, bottom),
    )

    private data class ElkInput(
        val graph: JsonObject,
        val edgeIndices: Map<String, List<Int>>,
    )

    private data class ElkInputNode(
        val id: String,
        val parentId: String?,
        val width: Float? = null,
        val height: Float? = null,
        val isGroup: Boolean = false,
        val label: String? = null,
        val labelSize: SceneSize? = null,
        val metadata: Map<String, String> = emptyMap(),
        val children: MutableList<ElkInputNode> = mutableListOf(),
        val layoutOptions: MutableMap<String, JsonElement> = linkedMapOf(),
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("parentId", parentId ?: "")
            put("isGroup", isGroup)
            width?.let { put("width", it) }
            height?.let { put("height", it) }
            if (layoutOptions.isNotEmpty()) {
                put("layoutOptions", layoutOptions.toJsonObject())
            }
            if (isGroup) {
                put(
                    "labels",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("text", label.orEmpty())
                                put("width", labelSize?.width ?: 0f)
                                put("height", labelSize?.height ?: 0f)
                            },
                        )
                    },
                )
                put(
                    "children",
                    buildJsonArray {
                        children.forEach { child -> add(child.toJson()) }
                    },
                )
            }
        }
    }

    private data class ElkNodeState(
        val id: String,
        var bounds: SceneRect,
        val elkBounds: SceneRect,
        val isGroup: Boolean,
    )

    private data class ElkEndpoint(
        val bounds: SceneRect,
        val shapeLayout: MermaidShapeLayout?,
        val isGroup: Boolean,
        val isRectangle: Boolean,
    ) {
        fun absoluteOutline(): List<ScenePoint>? =
            shapeLayout?.geometry?.outline?.map { point ->
                ScenePoint(bounds.center.x + point.x, bounds.center.y + point.y)
            }
    }

    private class FlowHierarchy(document: FlowchartDocument) {
        val parentById: Map<String, String>

        init {
            val parents = linkedMapOf<String, String>()
            document.subgraphs.forEach { subgraph ->
                subgraph.parentId?.let { parentId -> parents[subgraph.id] = parentId }
                subgraph.nodeIds.forEach { childId -> parents[childId] = subgraph.id }
            }
            parentById = parents
        }

        fun findCommonAncestor(
            first: String,
            second: String,
        ): String {
            val visited = linkedSetOf<String>()
            var current: String? = first
            if (first == second) {
                return parentById[first] ?: ROOT_ID
            }
            while (current != null) {
                visited += current
                if (current == second) {
                    return current
                }
                current = parentById[current]
            }
            current = second
            while (current != null) {
                if (current in visited) {
                    return current
                }
                current = parentById[current]
            }
            return ROOT_ID
        }
    }

    private enum class Axis {
        Horizontal,
        Vertical;

        fun opposite(): Axis = if (this == Horizontal) Vertical else Horizontal
    }

    private fun resolvePreset(name: String): ElkPreset = when (name) {
        "legacy" -> ElkPreset(
            layering = "NETWORK_SIMPLEX",
            placement = "BRANDES_KOEPF",
            containerPlacement = "BRANDES_KOEPF",
            alignment = "NONE",
            cycleBreaking = "GREEDY",
        )
        "modelOrder" -> ElkPreset(
            layering = "NETWORK_SIMPLEX",
            placement = "NETWORK_SIMPLEX",
            containerPlacement = "BRANDES_KOEPF",
            alignment = "NONE",
            cycleBreaking = "GREEDY_MODEL_ORDER",
        )
        "depthFirst" -> ElkPreset(
            layering = "NETWORK_SIMPLEX",
            placement = "NETWORK_SIMPLEX",
            containerPlacement = "BRANDES_KOEPF",
            alignment = "NONE",
            cycleBreaking = "DEPTH_FIRST",
        )
        else -> ElkPreset(
            layering = "NETWORK_SIMPLEX",
            placement = "BRANDES_KOEPF",
            containerPlacement = "BRANDES_KOEPF",
            alignment = "BALANCED",
            cycleBreaking = "DEPTH_FIRST",
        )
    }

    private data class ElkPreset(
        val layering: String,
        val placement: String,
        val containerPlacement: String,
        val alignment: String,
        val cycleBreaking: String,
    )

    private const val ROOT_ID = "root"
    private const val SUBGRAPH_PADDING = 24
    private const val DEFAULT_SUBGRAPH_SPACING_BASE_VALUE = 24
    private const val DEFAULT_SUBGRAPH_NODE_SPACING = 50
    private const val CONTAINER_PADDING = 15
    private const val RECTPACKING_CONTAINER_PADDING = 10
    private const val FLOW_GROUP_PADDING = 8f
    private const val PORTS_SURROUNDING_MARGIN = 12
    private const val PORTS_SURROUNDING =
        "[top=$PORTS_SURROUNDING_MARGIN,left=$PORTS_SURROUNDING_MARGIN," +
            "bottom=$PORTS_SURROUNDING_MARGIN,right=$PORTS_SURROUNDING_MARGIN]"
    private const val OUTLINE_RAY_STEPS = 20
    private const val DEPARTURE_AXIS_EPS = 0.000001f
    private const val DEGENERATE_ALIGNMENT_TOLERANCE = 0.5f
    private const val MARKER_BORDER_TOLERANCE = 1f
    private const val MIN_END_MARKER_SEGMENT_LENGTH = 8f
    private const val POINT_EPSILON = 0.0001f
    private const val TERMINAL_JOG_MAX = 16f
    private const val TERMINAL_RUN_MAX = 30f
    private const val JOG_EPSILON = 0.01f

    private val CONTAINER_ALGORITHMS = setOf(
        "elk.layered",
        "elk.box",
        "elk.rectpacking",
        "elk.stress",
        "elk.force",
        "elk.mrtree",
        "elk.radial",
        "elk.sporeOverlap",
    )

    private val CONTAINER_ALGORITHM_OVERRIDES = setOf(
        "nodeSize.constraints",
        "nodeSize.minimum",
        "elk.algorithm",
        "elk.aspectRatio",
        "elk.contentAlignment",
        "elk.expandNodes",
        "elk.padding",
        "elk.rectpacking.trybox",
        "elk.rectpacking.packing.compaction.rowHeightReevaluation",
        "elk.rectpacking.packing.compaction.iterations",
        "elk.rectpacking.whiteSpaceElimination.strategy",
        "elk.rectpacking.widthApproximation.strategy",
    )
}
