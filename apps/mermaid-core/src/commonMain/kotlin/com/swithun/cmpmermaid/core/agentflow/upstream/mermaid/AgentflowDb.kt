package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowShapeRegistry
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidFlowLayoutData
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidFlowLayoutEdge
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidFlowLayoutNode
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidUrlSanitizer

/**
 * Kotlin port of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/agentflow/agentflowDb.ts.
 */
internal class AgentflowDb(
    private val config: MermaidRenderOptions = MermaidRenderOptions(),
    private val frontmatterLineOffset: Int = 0,
) {
    private var vertexCounter = 0
    private val vertices = linkedMapOf<String, AgentflowVertex>()
    private val edges = mutableListOf<AgentflowEdge>()
    private val classes = linkedMapOf<String, AgentflowClass>()
    private val subgraphs = mutableListOf<AgentflowSubgraph>()
    private val subgraphLookup = linkedMapOf<String, AgentflowSubgraph>()
    private val globalNodes = linkedSetOf<String>()
    private val connectors = linkedMapOf<String, AgentflowVertex>()
    private var defaultEdgeInterpolate: String? = null
    private var defaultEdgeStyle: List<String> = emptyList()
    private var subgraphCounter = 0
    private var firstGraphFlag = true
    private var direction: String? = null
    private val elementMappings = mutableListOf<AgentflowElementMapping>()
    private val bareVertexMappings = mutableListOf<AgentflowElementMapping>()
    private val diagnostics = mutableListOf<AgentflowDiagnostic>()

    var accessibilityTitle: String? = null
        private set

    var accessibilityDescription: String? = null
        private set

    var diagramTitle: String? = null
        private set

    fun firstGraph(): Boolean {
        val result = firstGraphFlag
        firstGraphFlag = false
        return result
    }

    fun setDirection(value: String) {
        direction = when {
            '<' in value -> "RL"
            '^' in value -> "BT"
            '>' in value -> "LR"
            'v' in value -> "TB"
            value.trim() == "TD" -> "TB"
            else -> value.trim()
        }
    }

    fun getDirection(): String? = direction?.trim()

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value.trim()
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value.trim()
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun addVertex(
        id: String,
        text: AgentflowText? = null,
        type: String? = null,
        style: List<String>? = null,
        classNames: List<String>? = null,
        vertexDirection: String? = null,
        props: Map<String, String>? = null,
        metadata: String? = null,
        metadataLocation: AgentflowJisonLocation? = null,
    ): GMResult<Unit, MermaidError> {
        if (id.isBlank()) {
            return GMResult.Ok(Unit)
        }

        val metadataValues = when (val parsed = parseMetadata(metadata, metadataLocation)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val authoredShape = metadataValues["shape"]
        if (authoredShape is String) {
            metadataValues["shape"] = AgentflowShapes.resolveAlias(authoredShape)
        }

        val subgraph = subgraphLookup[id]
        if (subgraph != null && metadata != null) {
            subgraph.metadata.putAll(metadataValues)
            return GMResult.Ok(Unit)
        }
        val connector = connectors[id]
        if (connector != null && metadata != null) {
            connector.metadata.putAll(metadataValues)
            return GMResult.Ok(Unit)
        }
        if (id == RESERVED_CONNECTORS_ID) {
            return GMResult.Ok(Unit)
        }
        val edge = edges.firstOrNull { it.id == id }
        if (edge != null) {
            metadataValues["animate"]?.let { edge.animate = metadataBoolean(it) }
            metadataValues["animation"]?.let { edge.animation = it.toString() }
            metadataValues["curve"]?.let { edge.interpolate = it.toString() }
            edge.metadata.putAll(metadataValues)
            return GMResult.Ok(Unit)
        }

        val vertex = vertices.getOrPut(id) {
            AgentflowVertex(
                id = id,
                domId = "$DOM_ID_PREFIX$id-$vertexCounter",
            )
        }
        vertexCounter += 1

        if (text != null) {
            vertex.text = text.text.trim().removeSurrounding("\"")
            vertex.labelType = text.type
        } else if (vertex.text == null) {
            vertex.text = id
        }
        if (type != null) {
            vertex.type = type
        }
        style?.let(vertex.styles::addAll)
        classNames?.let(vertex.classes::addAll)
        if (vertexDirection != null) {
            vertex.direction = vertexDirection
        }
        props?.let(vertex.props::putAll)

        if (metadata != null) {
            vertex.metadata.putAll(metadataValues)
        }
        val shape = metadataValues["shape"]
        if (shape != null) {
            val authored = authoredShape ?: shape
            if (authored !is String) {
                return positionedParseError(
                    message = "No such shape: $authored.",
                    location = metadataLocation,
                )
            }
            if (authored != authored.lowercase() || '_' in authored) {
                return positionedParseError(
                    message = "No such shape: $authored. Shape names should be lowercase.",
                    location = metadataLocation,
                )
            }
            val resolved = shape as? String
                ?: return positionedParseError(
                    message = "No such shape: $shape.",
                    location = metadataLocation,
                )
            if (!FlowShapeRegistry.isValid(resolved)) {
                return positionedParseError(
                    message = "No such shape: $resolved.",
                    location = metadataLocation,
                )
            }
            vertex.type = resolved
        }
        metadataValues["label"]?.toString()?.takeIf(String::isNotEmpty)?.let { label ->
            vertex.text = label
            vertex.labelType = labelType(metadataValues["labelType"]?.toString())
        }
        return GMResult.Ok(Unit)
    }

    fun addLink(
        starts: List<String>,
        ends: List<String>,
        link: AgentflowLink,
    ): GMResult<Unit, MermaidError> {
        val userId = link.id?.replace("@", "")
        starts.forEachIndexed { startIndex, start ->
            ends.forEachIndexed { endIndex, end ->
                val id = userId?.takeIf {
                    startIndex == starts.lastIndex && endIndex == 0
                }
                when (val added = addSingleLink(start, end, link, id)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return added
                }
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun addSingleLink(
        start: String,
        end: String,
        link: AgentflowLink,
        requestedId: String?,
    ): GMResult<Unit, MermaidError> {
        if (edges.size >= config.maxEdges) {
            return parseError(
                "Edge limit exceeded. ${edges.size} edges found, but the limit is ${config.maxEdges}.",
            )
        }
        val edge = AgentflowEdge(
            start = start,
            end = end,
            type = link.type,
            stroke = link.stroke,
            length = link.length?.coerceAtMost(10),
            interpolate = defaultEdgeInterpolate,
            semantic = link.semantic,
        )
        link.text?.let { label ->
            edge.text = label.text.trim().removeSurrounding("\"")
            edge.labelType = label.type
        }
        if (requestedId != null && edges.none { it.id == requestedId }) {
            edge.id = requestedId
            edge.isUserDefinedId = true
        } else {
            val existing = edges.count { it.start == start && it.end == end }
            val counter = if (existing == 0) 0 else existing + 1
            edge.id = edgeId(start, end, counter)
        }
        edges += edge
        return GMResult.Ok(Unit)
    }

    fun updateLinkInterpolate(
        positions: List<LinkPosition>,
        interpolate: String,
    ): GMResult<Unit, MermaidError> {
        positions.forEach { position ->
            when (position) {
                LinkPosition.Default -> defaultEdgeInterpolate = interpolate
                is LinkPosition.Index -> {
                    val edge = edges.getOrNull(position.value)
                        ?: return parseError(linkIndexError(position.value))
                    edge.interpolate = interpolate
                }
            }
        }
        return GMResult.Ok(Unit)
    }

    fun updateLink(
        positions: List<LinkPosition>,
        style: List<String>,
    ): GMResult<Unit, MermaidError> {
        positions.forEach { position ->
            when (position) {
                LinkPosition.Default -> defaultEdgeStyle = style
                is LinkPosition.Index -> {
                    val edge = edges.getOrNull(position.value)
                        ?: return parseError(linkIndexError(position.value))
                    val resolved = style.toMutableList()
                    if (resolved.isNotEmpty() && resolved.none { it.startsWith("fill") }) {
                        resolved += "fill:none"
                    }
                    edge.style = resolved
                }
            }
        }
        return GMResult.Ok(Unit)
    }

    fun addClass(ids: String, source: List<String>) {
        val styles = source
            .joinToString(",")
            .replace("\\,", STYLE_COMMA_SENTINEL)
            .replace(",", ";")
            .replace(STYLE_COMMA_SENTINEL, ",")
            .split(';')
        ids.split(',').forEach { id ->
            val definition = classes.getOrPut(id) { AgentflowClass(id) }
            styles.forEach { declaration ->
                if ("color" in declaration) {
                    definition.textStyles += declaration.replace("fill", "bgFill")
                }
                definition.styles += declaration
            }
        }
    }

    fun setClass(ids: String, className: String) {
        ids.split(',').forEach { id ->
            vertices[id]?.classes?.add(className)
            edges.firstOrNull { it.id == id }?.classes?.add(className)
            subgraphLookup[id]?.classes?.add(className)
        }
    }

    fun setTooltip(ids: String, tooltip: String?) {
        if (tooltip == null) {
            return
        }
        ids.split(',').forEach { id ->
            vertices[id]?.tooltip = tooltip
        }
    }

    fun setClickEvent(
        ids: String,
        functionName: String?,
        functionArgs: String? = null,
    ) {
        if (functionName != null && config.securityLevel == MermaidSecurityLevel.Loose) {
            ids.split(',').forEach { id ->
                vertices[id]?.let { vertex ->
                    vertex.callbackName = functionName
                    vertex.callbackArgs = functionArgs
                }
            }
        }
        setClass(ids, "clickable")
    }

    fun setLink(
        ids: String,
        link: String,
        target: String? = null,
    ) {
        ids.split(',').forEach { id ->
            vertices[id]?.let { vertex ->
                vertex.link = MermaidUrlSanitizer.sanitize(link)
                vertex.linkTarget = target
            }
        }
        setClass(ids, "clickable")
    }

    fun addSubgraph(
        idValue: AgentflowText?,
        items: List<AgentflowDocumentItem>,
        titleValue: AgentflowText?,
    ): GMResult<String, MermaidError> {
        var id = idValue?.text?.trim()
        val sourceTitle = titleValue?.text.orEmpty()
        if (idValue === titleValue && sourceTitle.any(Char::isWhitespace)) {
            id = null
        }

        val nodes = linkedSetOf<String>()
        var localDirection: String? = null
        items.forEach { item ->
            when (item) {
                is AgentflowDocumentItem.Nodes -> nodes += item.ids
                is AgentflowDocumentItem.Subgraph -> nodes += item.id
                is AgentflowDocumentItem.Direction -> localDirection = item.value
                AgentflowDocumentItem.Empty -> Unit
            }
        }
        val resolvedId = id ?: "subGraph$subgraphCounter"
        subgraphCounter += 1
        val candidate = AgentflowSubgraph(
            id = resolvedId,
            nodes = nodes
                .filterNot { childId ->
                    childId == resolvedId ||
                        childId in globalNodes ||
                        subgraphs.any { childId in it.nodes }
                }
                .toMutableList(),
            title = sourceTitle.trim(),
            direction = localDirection ?: if (config.inheritDirection) getDirection() else null,
            labelType = labelType(titleValue?.type?.name?.lowercase()),
        )
        val existing = subgraphLookup[resolvedId]
        if (existing == null) {
            subgraphs += candidate
            subgraphLookup[resolvedId] = candidate
        } else {
            candidate.nodes.forEach { child ->
                if (child !in existing.nodes) {
                    existing.nodes += child
                }
            }
            if (candidate.title.isNotEmpty()) {
                existing.title = candidate.title
            }
            candidate.direction?.let { existing.direction = it }
        }
        return GMResult.Ok(resolvedId)
    }

    fun addGlobal(items: List<AgentflowDocumentItem>) {
        items.forEach { item ->
            if (item is AgentflowDocumentItem.Nodes) {
                item.ids.map(String::trim).filter(String::isNotEmpty).forEach(globalNodes::add)
            }
        }
        subgraphs.forEach { subgraph ->
            subgraph.nodes.removeAll(globalNodes)
        }
    }

    fun addConnector(
        idValue: AgentflowText,
        titleValue: AgentflowText?,
    ): String {
        val id = idValue.text.trim()
        if (id.isEmpty()) {
            return ""
        }
        val title = titleValue?.text?.trim().orEmpty()
        val resolvedTitle = title.ifEmpty { id }
        val connector = connectors[id]
        if (connector == null) {
            val created = AgentflowVertex(
                id = id,
                domId = "$DOM_ID_PREFIX$id-$vertexCounter",
                text = resolvedTitle,
                isConnector = true,
            )
            connectors[id] = created
            vertices[id] = created
            vertexCounter += 1
        } else if (title.isNotEmpty()) {
            connector.text = resolvedTitle
        }
        return id
    }

    fun destructLink(
        endSource: String,
        startSource: String? = null,
    ): GMResult<AgentflowLink, MermaidError> {
        val end = destructEndLink(endSource)
        if (startSource == null) {
            return GMResult.Ok(end)
        }
        val start = destructStartLink(startSource)
        if (start.stroke != end.stroke) {
            return GMResult.Ok(AgentflowLink(type = "INVALID", stroke = "INVALID"))
        }
        start.type = end.type
        start.length = end.length
        start.semantic = edgeSemantic(start.type, start.stroke)
        return GMResult.Ok(start)
    }

    private fun destructStartLink(source: String): AgentflowLink {
        val value = source.trim()
        return AgentflowLink(
            type = "arrow_open",
            stroke = if ('.' in value) "dotted" else "normal",
        )
    }

    private fun destructEndLink(source: String): AgentflowLink {
        val value = source.trim()
        var line = value.dropLast(1)
        val type = when (value.lastOrNull()) {
            'x' -> "arrow_cross"
            '>' -> "arrow_point"
            '-', '.' -> {
                line = value
                "arrow_open"
            }
            else -> "arrow_open"
        }
        val dots = line.count { it == '.' }
        val stroke = if (dots > 0) "dotted" else "normal"
        val length = if (dots > 0) dots else line.length - 1
        return AgentflowLink(
            type = type,
            stroke = stroke,
            length = length,
            semantic = edgeSemantic(type, stroke),
        )
    }

    private fun edgeSemantic(type: String, stroke: String): AgentflowEdgeSemantic? = when {
        type == "arrow_point" && stroke == "normal" -> AgentflowEdgeSemantic.Sequence
        type == "arrow_cross" && stroke == "normal" -> AgentflowEdgeSemantic.Failure
        type == "arrow_open" && stroke == "dotted" -> AgentflowEdgeSemantic.Reference
        else -> null
    }

    fun vertices(): Map<String, AgentflowVertex> = vertices

    fun edges(): List<AgentflowEdge> = edges

    fun subgraphs(): List<AgentflowSubgraph> = subgraphs

    fun connectors(): List<AgentflowVertex> = connectors.values.toList()

    fun getTools(): List<AgentflowVertex> =
        vertices.values.filter(::isToolDefinition)

    fun isToolDefinition(vertex: AgentflowVertex): Boolean =
        vertex.type in TOOL_SHAPES

    fun config(): MermaidRenderOptions = config

    /**
     * Mermaid.js 12.0.0: agentflowDb.ts -> getData.
     */
    fun getData(
        paletteLength: Int = DEFAULT_PALETTE_LENGTH,
        colorTheme: Boolean = paletteLength > 0,
        hasBackgroundPalette: Boolean = colorTheme,
    ): MermaidFlowLayoutData {
        val parentByNode = linkedMapOf<String, String>()
        val groupIds = linkedSetOf<String>()
        val hiddenIds = linkedSetOf<String>()
        val collapsedAncestorById = linkedMapOf<String, String>()

        fun collectDescendants(
            subgraphId: String,
            ancestor: String,
            seen: MutableSet<String>,
        ) {
            if (!seen.add(subgraphId)) {
                return
            }
            val subgraph = subgraphLookup[subgraphId] ?: return
            subgraph.nodes.forEach { childId ->
                if (childId == ancestor || childId in seen) {
                    return@forEach
                }
                hiddenIds += childId
                collapsedAncestorById[childId] = ancestor
                collectDescendants(childId, ancestor, seen)
            }
        }
        subgraphs.forEach { subgraph ->
            if (subgraph.metadata["view"] == "collapsed" && subgraph.id !in hiddenIds) {
                collectDescendants(subgraph.id, subgraph.id, mutableSetOf())
            }
        }

        fun wouldCloseCycle(childId: String, parentId: String): Boolean {
            val seen = mutableSetOf<String>()
            var current: String? = parentId
            while (current != null && seen.add(current)) {
                if (current == childId) {
                    return true
                }
                current = parentByNode[current]
            }
            return false
        }
        subgraphs.asReversed().forEach { subgraph ->
            if (subgraph.id in hiddenIds) {
                return@forEach
            }
            if (subgraph.nodes.isNotEmpty()) {
                groupIds += subgraph.id
            }
            subgraph.nodes.forEach { childId ->
                if (wouldCloseCycle(childId, subgraph.id)) {
                    emitWarning(
                        id = AgentflowDiagnosticId.ContainmentViolation,
                        message = "Container \"${subgraph.id}\" cannot contain \"$childId\" because " +
                            "\"$childId\" already contains it. The nesting that would close the loop is dropped.",
                        nodeId = childId,
                    )
                } else {
                    parentByNode[childId] = subgraph.id
                }
            }
        }

        val containerOrder = containerOrder(parentByNode)
        val nodes = mutableListOf<MermaidFlowLayoutNode>()
        subgraphs.asReversed().forEach { subgraph ->
            if (subgraph.id in hiddenIds) {
                return@forEach
            }
            val collapsed = subgraph.metadata["view"] == "collapsed"
            nodes += MermaidFlowLayoutNode(
                id = subgraph.id,
                label = subgraph.title,
                labelType = subgraph.labelType,
                parentId = parentByNode[subgraph.id],
                padding = CONTAINER_PADDING,
                minWidth = null,
                look = config.look,
                cssStyles = emptyList(),
                cssCompiledStyles = buildList {
                    // Mermaid.js 12.0.0: diagrams/agentflow/styles.ts
                    // -> .flow-cluster rect. Palette fills only exist for
                    // colour themes that carry a background palette.
                    if (!collapsed) {
                        if (!colorTheme || !hasBackgroundPalette) {
                            add("fill:none")
                        }
                        add("stroke-width:0.75px")
                    }
                    addAll(compiledStyles(subgraph.classes))
                },
                cssClasses = subgraph.classes.joinToString(" "),
                shape = if (collapsed) "collapsedGroup" else "flowGroup",
                dir = subgraph.direction,
                isGroup = !collapsed,
                metadata = metadataForLayout(
                    if (collapsed) subgraph.metadata + ("containerType" to "flow") else subgraph.metadata,
                ),
                colorIndex = containerOrder[subgraph.id]
                    ?.let { AgentflowShapes.containerSlot(it, paletteLength) }
                    ?.takeIf { colorTheme && paletteLength > 0 },
            )
        }

        vertices.values.forEach { vertex ->
            if (vertex.id in hiddenIds) {
                return@forEach
            }
            val existingIndex = nodes.indexOfFirst { node -> node.id == vertex.id }
            if (existingIndex >= 0) {
                val existing = nodes[existingIndex]
                nodes[existingIndex] = existing.copy(
                    cssStyles = vertex.styles,
                    cssCompiledStyles =
                        existing.cssCompiledStyles + compiledStyles(vertex.classes),
                    cssClasses = vertex.classes.joinToString(" "),
                )
                return@forEach
            }
            val normalizedShape = normalizeShape(typeFromVertex(vertex), vertex.id)
            val kind = deriveVertexKind(vertex, normalizedShape)
            nodes += MermaidFlowLayoutNode(
                id = vertex.id,
                label = vertex.text,
                labelType = vertex.labelType,
                parentId = parentByNode[vertex.id],
                padding = AGENTFLOW_NODE_PADDING,
                minWidth = config.minNodeWidth,
                look = config.look,
                cssStyles = vertex.styles,
                cssCompiledStyles = compiledStyles(
                    listOf("default", "node") + vertex.classes,
                ),
                cssClasses = "default ${vertex.classes.joinToString(" ")}",
                shape = if (vertex.id in groupIds) "rect" else normalizedShape,
                dir = vertex.direction,
                isGroup = vertex.id in groupIds,
                metadata = metadataForLayout(vertex.metadata),
                colorIndex = AgentflowShapes.kindSlots[kind]
                    ?.takeIf { colorTheme && paletteLength > 0 },
                link = vertex.link,
                linkTarget = vertex.linkTarget,
                tooltip = vertex.tooltip,
                callbackName = vertex.callbackName,
                callbackArgs = vertex.callbackArgs,
            )
        }

        val layoutEdges = buildList {
            edges.forEachIndexed { index, rawEdge ->
                val start = collapsedAncestorById[rawEdge.start] ?: rawEdge.start
                val end = collapsedAncestorById[rawEdge.end] ?: rawEdge.end
                if (start == end && rawEdge.start != rawEdge.end) {
                    return@forEachIndexed
                }
                val (arrowStart, arrowEnd) = destructEdgeType(rawEdge.type)
                val invisible = rawEdge.stroke == "invisible"
                add(
                    MermaidFlowLayoutEdge(
                        id = rawEdge.id ?: edgeId(start, end, index),
                        isUserDefinedId = rawEdge.isUserDefinedId,
                        start = start,
                        end = end,
                        type = rawEdge.type ?: "normal",
                        label = rawEdge.text,
                        labelType = rawEdge.labelType,
                        thickness = rawEdge.stroke,
                        minimumLength = rawEdge.length,
                        classes = if (invisible) {
                            ""
                        } else {
                            "edge-thickness-normal edge-pattern-solid flowchart-link"
                        },
                        arrowTypeStart = if (invisible || rawEdge.type == "arrow_open") {
                            "none"
                        } else {
                            arrowStart
                        },
                        arrowTypeEnd = if (invisible || rawEdge.type == "arrow_open") {
                            "none"
                        } else {
                            arrowEnd
                        },
                        cssCompiledStyles = compiledStyles(rawEdge.classes),
                        styles = defaultEdgeStyle + rawEdge.style.orEmpty(),
                        pattern = rawEdge.stroke,
                        animate = rawEdge.animate,
                        animation = rawEdge.animation,
                        curve = rawEdge.interpolate ?: defaultEdgeInterpolate ?: config.curve,
                        look = config.look,
                    ),
                )
            }
        }
        return MermaidFlowLayoutData(nodes = nodes, edges = layoutEdges)
    }

    fun semanticModel(): AgentflowSemanticModel {
        val subgraphIds = subgraphs.mapTo(linkedSetOf()) { it.id }
        val semanticVertices = buildList {
            vertices.values.forEach { vertex ->
                if (vertex.id in subgraphIds || vertex.isConnector) {
                    return@forEach
                }
                val shape = AgentflowShapes.resolveAlias(vertex.type)
                add(
                    AgentflowSemanticVertex(
                        id = vertex.id,
                        label = vertex.text,
                        shape = shape,
                        kind = deriveVertexKind(vertex, shape),
                        metadata = semanticMetadata(vertex.metadata),
                    ),
                )
            }
        }
        return AgentflowSemanticModel(
            direction = direction,
            vertices = semanticVertices,
            edges = edges.map { edge ->
                AgentflowSemanticEdge(
                    start = edge.start,
                    end = edge.end,
                    id = edge.id,
                    label = edge.text.takeIf(String::isNotEmpty),
                    type = edge.type,
                    stroke = edge.stroke,
                    semantic = edge.semantic,
                    length = edge.length,
                    metadata = edge.metadata.toMap(),
                )
            },
            subgraphs = subgraphs.map { subgraph ->
                AgentflowSemanticSubgraph(
                    id = subgraph.id,
                    title = subgraph.title,
                    nodes = subgraph.nodes.toList(),
                    metadata = semanticMetadata(subgraph.metadata),
                    direction = subgraph.direction,
                )
            },
            connectors = connectors.values.map { connector ->
                AgentflowSemanticConnector(
                    id = connector.id,
                    title = connector.text?.takeUnless { it == connector.id },
                    metadata = semanticMetadata(connector.metadata),
                )
            },
            diagnostics = diagnostics.toList(),
        )
    }

    fun addVertexMapping(
        id: String,
        shape: String?,
        location: AgentflowJisonLocation,
    ) {
        val mapping = pushMapping(id, AgentflowStatementType.Vertex, location) ?: return
        if (shape == null) {
            bareVertexMappings += mapping
        }
    }

    fun extendVertexMapping(
        id: String,
        location: AgentflowJisonLocation,
    ) {
        val end = sourcePosition(location)
        val mapping = elementMappings
            .asReversed()
            .firstOrNull { it.type == AgentflowStatementType.Vertex && it.id == id }
        if (mapping == null) {
            pushMapping(id, AgentflowStatementType.Attachment, location)
            return
        }
        if (bareVertexMappings.any { it === mapping }) {
            mapping.type = AgentflowStatementType.Attachment
            bareVertexMappings.removeAll { it === mapping }
        }
        val extendsPastEnd =
            end.endLine > mapping.position.endLine ||
                (
                    end.endLine == mapping.position.endLine &&
                        end.endColumn > mapping.position.endColumn
                    )
        if (extendsPastEnd) {
            mapping.position = mapping.position.copy(
                endLine = end.endLine,
                endColumn = end.endColumn,
                endIndex = end.endIndex,
            )
        }
    }

    fun addEdgeMapping(
        toNodes: List<String>,
        location: AgentflowJisonLocation,
    ) {
        pushMapping(
            id = toNodes.takeIf(List<String>::isNotEmpty)?.joinToString(">") ?: "edge",
            type = AgentflowStatementType.Edge,
            location = location,
        )
    }

    fun addSubgraphMapping(
        idValue: AgentflowText?,
        start: AgentflowJisonLocation,
        end: AgentflowJisonLocation,
    ) {
        val id = idValue?.text.orEmpty()
        if (id.isEmpty()) {
            return
        }
        val startPosition = sourcePosition(start)
        val endPosition = sourcePosition(end)
        elementMappings += AgentflowElementMapping(
            id = id,
            type = AgentflowStatementType.Subgraph,
            position = startPosition.copy(
                endLine = endPosition.endLine,
                endColumn = endPosition.endColumn,
                endIndex = endPosition.endIndex,
            ),
        )
    }

    fun addConnectorMapping(
        idValue: AgentflowText,
        start: AgentflowJisonLocation,
        end: AgentflowJisonLocation,
    ) {
        val id = idValue.text.trim()
        if (id.isEmpty()) {
            return
        }
        val startPosition = sourcePosition(start)
        val endPosition = sourcePosition(end)
        elementMappings += AgentflowElementMapping(
            id = id,
            type = AgentflowStatementType.Connector,
            position = startPosition.copy(
                endLine = endPosition.endLine,
                endColumn = endPosition.endColumn,
                endIndex = endPosition.endIndex,
            ),
        )
    }

    fun elementMappings(): List<AgentflowElementMapping> = elementMappings.toList()

    fun getElementById(id: String): AgentflowElementMapping? =
        elementMappings.firstOrNull { it.id == id }

    fun getElementsOnLine(line: Int): List<AgentflowElementMapping> =
        elementMappings.filter { line in it.position.startLine..it.position.endLine }

    fun getElementAtPosition(
        line: Int,
        column: Int,
    ): AgentflowElementMapping? =
        elementMappings
            .filter { mapping ->
                val position = mapping.position
                line in position.startLine..position.endLine &&
                    (line != position.startLine || column >= position.startColumn) &&
                    (line != position.endLine || column <= position.endColumn)
            }
            .minWithOrNull(
                compareBy<AgentflowElementMapping>(
                    { it.position.endLine - it.position.startLine },
                    { it.position.endColumn - it.position.startColumn },
                ),
            )

    fun mappingStats(): AgentflowMappingStats =
        AgentflowMappingStats(
            vertices = elementMappings.count { it.type == AgentflowStatementType.Vertex },
            edges = elementMappings.count { it.type == AgentflowStatementType.Edge },
            subgraphs = elementMappings.count { it.type == AgentflowStatementType.Subgraph },
            connectors = elementMappings.count { it.type == AgentflowStatementType.Connector },
            attachments = elementMappings.count { it.type == AgentflowStatementType.Attachment },
            totalElements = elementMappings.size,
        )

    fun emitWarning(
        id: AgentflowDiagnosticId,
        message: String,
        nodeId: String? = null,
        edgeId: String? = null,
    ) {
        emitDiagnostic(id, AgentflowDiagnosticSeverity.Warning, message, nodeId, edgeId)
    }

    fun emitError(
        id: AgentflowDiagnosticId,
        message: String,
        nodeId: String? = null,
        edgeId: String? = null,
    ) {
        emitDiagnostic(id, AgentflowDiagnosticSeverity.Error, message, nodeId, edgeId)
    }

    fun diagnostics(): List<AgentflowDiagnostic> = diagnostics.toList()

    private fun emitDiagnostic(
        id: AgentflowDiagnosticId,
        severity: AgentflowDiagnosticSeverity,
        message: String,
        nodeId: String?,
        edgeId: String?,
    ) {
        val anchorId = nodeId ?: edgeId
        val diagnostic = AgentflowDiagnostic(
            id = id,
            severity = severity,
            message = message,
            nodeId = nodeId,
            edgeId = edgeId.takeIf { nodeId == null },
            position = anchorId?.let(::getElementById)?.position,
        )
        if (diagnostic !in diagnostics) {
            diagnostics += diagnostic
        }
    }

    private fun normalizeShape(
        source: String?,
        nodeId: String,
    ): String {
        var shape = source
        if (shape == null || shape == "squareRect" || shape == "rect") {
            shape = AgentflowShapes.DEFAULT_SHAPE
        }
        if (shape in AgentflowShapes.removed) {
            emitError(
                id = AgentflowDiagnosticId.ShapeRemoved,
                message = "shape \"$shape\" was removed in v0.8.1, using " +
                    "\"${AgentflowShapes.DEFAULT_SHAPE}\"",
                nodeId = nodeId,
            )
            return AgentflowShapes.DEFAULT_SHAPE
        }
        if (shape !in AgentflowShapes.allowed) {
            emitWarning(
                id = AgentflowDiagnosticId.ShapeUnsupported,
                message = "shape \"$shape\" is not supported, using " +
                    "\"${AgentflowShapes.DEFAULT_SHAPE}\"",
                nodeId = nodeId,
            )
            return AgentflowShapes.DEFAULT_SHAPE
        }
        return shape
    }

    private fun typeFromVertex(vertex: AgentflowVertex): String? = when (
        val resolved = AgentflowShapes.resolveAlias(vertex.type)
    ) {
        null, "square" -> "squareRect"
        "round" -> "roundedRect"
        else -> resolved
    }

    private fun deriveVertexKind(
        vertex: AgentflowVertex,
        resolvedShape: String?,
    ): AgentflowVertexKind = when {
        vertex.isConnector -> AgentflowVertexKind.Connector
        isToolDefinition(vertex) -> AgentflowVertexKind.Tool
        resolvedShape == "hexagon" || resolvedShape == "hex" -> AgentflowVertexKind.Action
        resolvedShape == "lean-right" || resolvedShape == "lean_right" -> AgentflowVertexKind.Input
        resolvedShape == "lin-doc" || resolvedShape == "lined-document" ->
            AgentflowVertexKind.ReferenceDocument
        resolvedShape == "diamond" -> AgentflowVertexKind.Decision
        else -> AgentflowVertexKind.Task
    }

    private fun containerOrder(parentByNode: Map<String, String>): Map<String, Int> {
        val children = linkedMapOf<String, MutableList<String>>()
        subgraphs.forEach { subgraph ->
            parentByNode[subgraph.id]?.let { parentId ->
                children.getOrPut(parentId, ::mutableListOf) += subgraph.id
            }
        }
        val order = linkedMapOf<String, Int>()
        fun walk(id: String) {
            if (id in order) {
                return
            }
            order[id] = order.size
            children[id].orEmpty().forEach(::walk)
        }
        subgraphs.forEach { subgraph ->
            if (subgraph.id !in parentByNode) {
                walk(subgraph.id)
            }
        }
        return order
    }

    private fun destructEdgeType(type: String?): Pair<String, String> {
        var start = "none"
        var end = "arrow_point"
        when (type) {
            "arrow_point", "arrow_circle", "arrow_cross", "arrow_hierarchy" -> end = type
            "double_arrow_point", "double_arrow_circle", "double_arrow_cross" -> {
                start = type.removePrefix("double_")
                end = start
            }
        }
        return start to end
    }

    private fun compiledStyles(classNames: List<String>): List<String> = buildList {
        classNames.forEach { className ->
            classes[className]?.let { definition ->
                addAll(definition.styles.map(String::trim))
                addAll(definition.textStyles.map(String::trim))
            }
        }
    }

    private fun parseMetadata(
        source: String?,
        location: AgentflowJisonLocation?,
    ): GMResult<MutableMap<String, Any?>, MermaidError> {
        if (source == null) {
            return GMResult.Ok(linkedMapOf())
        }
        // Mermaid.js 12.0.0: agentflow-empty-metadata.spec.ts (issue #83).
        // js-yaml loads an empty document as null; Kaml reports it as an error.
        if (source.isBlank()) {
            return GMResult.Ok(linkedMapOf())
        }
        val inline = '\n' !in source
        val yamlSource = if (inline) "{\n$source\n}" else "$source\n"
        val parsed = when (val result = parseYaml(yamlSource, source, location)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> {
                val stripped = source.takeUnless { inline }?.let(::stripLineTrailingCommas)
                if (stripped != null && stripped != source) {
                    when (val retried = parseYaml("$stripped\n", source, location)) {
                        is GMResult.Ok -> retried.value
                        is GMResult.Err -> return result
                    }
                } else {
                    return result
                }
            }
        }
        if (parsed is YamlNull) {
            return GMResult.Ok(linkedMapOf())
        }
        val map = parsed as? YamlMap
            ?: return positionedParseError("Agentflow metadata must be a map", location)
        return GMResult.Ok(
            buildMap {
                map.entries.forEach { (key, value) ->
                    if (key.content !in PROTOTYPE_KEYS) {
                        put(key.content, yamlValue(value))
                    }
                }
            }.toMutableMap(),
        )
    }

    private fun parseYaml(
        yamlSource: String,
        originalSource: String,
        location: AgentflowJisonLocation?,
    ): GMResult<YamlNode, MermaidError> =
        try {
            GMResult.Ok(Yaml.default.parseToYamlNode(yamlSource))
        } catch (failure: Exception) {
            positionedParseError(
                message = failure.message ?: "Invalid agentflow metadata",
                location = location,
                source = originalSource,
            )
        }

    private fun yamlValue(node: YamlNode): Any? = when (node) {
        is YamlNull -> null
        is YamlMap -> buildMap {
            node.entries.forEach { (key, value) ->
                if (key.content !in PROTOTYPE_KEYS) {
                    put(key.content, yamlValue(value))
                }
            }
        }
        is YamlList -> node.items.map(::yamlValue)
        is YamlScalar -> scalarValue(node.content)
        else -> node.toString()
    }

    private fun scalarValue(value: String): Any = when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        INTEGER.matches(value) -> value.toLongOrNull() ?: value
        DECIMAL.matches(value) -> value.toDoubleOrNull() ?: value
        else -> value
    }

    private fun stripLineTrailingCommas(source: String): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        var flowDepth = 0
        var blockScalarIndent: Int? = null
        return source.lineSequence().joinToString("\n") { line ->
            val indent = line.length - line.trimStart().length
            val activeBlockScalarIndent = blockScalarIndent
            if (activeBlockScalarIndent != null && (line.isBlank() || indent > activeBlockScalarIndent)) {
                return@joinToString line
            }
            blockScalarIndent = null
            var commentStart = -1
            var index = 0
            while (index < line.length) {
                val character = line[index]
                when {
                    inDoubleQuote && character == '\\' -> index += 1
                    inDoubleQuote && character == '"' -> inDoubleQuote = false
                    inSingleQuote && character == '\'' -> inSingleQuote = false
                    !inSingleQuote && !inDoubleQuote && character == '"' -> inDoubleQuote = true
                    !inSingleQuote && !inDoubleQuote && character == '\'' -> inSingleQuote = true
                    !inSingleQuote && !inDoubleQuote &&
                        character == '#' &&
                        (index == 0 || line[index - 1].isWhitespace()) -> {
                        commentStart = index
                        break
                    }
                    !inSingleQuote && !inDoubleQuote && character in "[{" -> flowDepth += 1
                    !inSingleQuote && !inDoubleQuote && character in "]}" ->
                        flowDepth = (flowDepth - 1).coerceAtLeast(0)
                }
                index += 1
            }
            if (inSingleQuote || inDoubleQuote || flowDepth > 0) {
                return@joinToString line
            }
            val code = if (commentStart >= 0) line.take(commentStart) else line
            val comment = if (commentStart >= 0) line.drop(commentStart) else ""
            if (BLOCK_SCALAR.matches(code)) {
                blockScalarIndent = indent
                line
            } else {
                code.replace(TRAILING_COMMA, "$1") + comment
            }
        }
    }

    private fun sourcePosition(location: AgentflowJisonLocation): AgentflowSourcePosition =
        AgentflowSourcePosition(
            startLine = location.firstLine + frontmatterLineOffset,
            startColumn = location.firstColumn,
            endLine = location.lastLine + frontmatterLineOffset,
            endColumn = location.lastColumn,
            startIndex = location.startIndex,
            endIndex = location.endIndex,
        )

    private fun pushMapping(
        id: String,
        type: AgentflowStatementType,
        location: AgentflowJisonLocation,
    ): AgentflowElementMapping? {
        if (id.isEmpty()) {
            return null
        }
        return AgentflowElementMapping(id, type, sourcePosition(location))
            .also(elementMappings::add)
    }

    private fun semanticMetadata(source: Map<String, Any?>): Map<String, Any?> =
        source.filterKeys { it !in SEMANTIC_METADATA_SKIP_KEYS }

    private fun metadataForLayout(source: Map<String, Any?>): Map<String, String> =
        source.mapValues { (_, value) -> metadataString(value) }

    private fun metadataString(value: Any?): String = when (value) {
        null -> "null"
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { metadataString(it) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, entry) ->
            "$key: ${metadataString(entry)}"
        }
        else -> value.toString()
    }

    private fun metadataBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    private fun labelType(value: String?): FlowLabelType = when (value) {
        "markdown" -> FlowLabelType.Markdown
        "string" -> FlowLabelType.String
        "text" -> FlowLabelType.Text
        else -> FlowLabelType.Markdown
    }

    private fun edgeId(from: String, to: String, counter: Int): String =
        "L_${from}_${to}_$counter"

    private fun linkIndexError(index: Int): String =
        "The index $index for linkStyle is out of bounds. Valid indices for linkStyle are " +
            "between 0 and ${edges.lastIndex}. (Help: Ensure that the index is within the range " +
            "of existing edges.)"

    private fun <T> positionedParseError(
        message: String,
        location: AgentflowJisonLocation?,
        source: String? = null,
    ): GMResult<T, MermaidError> {
        val relativeLine = YAML_LOCATION.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val relativeColumn = YAML_LOCATION.find(message)
            ?.groupValues
            ?.getOrNull(2)
            ?.toIntOrNull()
        val inline = source?.contains('\n') == false
        val line = when {
            location == null -> 1
            relativeLine == null -> location.firstLine + frontmatterLineOffset
            inline -> location.firstLine + frontmatterLineOffset
            else -> location.firstLine + frontmatterLineOffset + relativeLine - 1
        }
        val column = when {
            location == null -> 1
            relativeColumn == null -> location.firstColumn + 1
            inline -> location.firstColumn + 2 + relativeColumn
            relativeLine == 1 -> location.firstColumn + 2 + relativeColumn
            else -> relativeColumn
        }
        return GMResult.Err(MermaidError.Parse(line, column, message))
    }

    private fun <T> parseError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(1, 1, message))

    internal sealed interface LinkPosition {
        object Default : LinkPosition

        data class Index(val value: Int) : LinkPosition
    }

    private companion object {
        const val DOM_ID_PREFIX = "agentflow-"
        const val RESERVED_CONNECTORS_ID = "connectors"
        const val CONTAINER_PADDING = 8f
        const val AGENTFLOW_NODE_PADDING = 8f
        const val DEFAULT_PALETTE_LENGTH = 12
        const val STYLE_COMMA_SENTINEL = "\u0000comma\u0000"
        val TOOL_SHAPES = setOf(
            "subroutine",
            "subprocess",
            "subproc",
            "framed-rectangle",
            "tool",
        )
        val SEMANTIC_METADATA_SKIP_KEYS = setOf(
            "shape",
            "view",
            "icon",
            "img",
            "form",
            "pos",
            "w",
            "h",
            "class",
            "style",
            "labelType",
        )
        val PROTOTYPE_KEYS = setOf("__proto__", "constructor", "prototype")
        val INTEGER = Regex("""[-+]?\d+""")
        val DECIMAL = Regex("""[-+]?(?:\d+\.\d*|\d*\.\d+)(?:[eE][-+]?\d+)?""")
        val BLOCK_SCALAR = Regex(""".*:\s*[>|][\d+-]*\s*$""")
        val TRAILING_COMMA = Regex(""",([\t ]*)$""")
        val YAML_LOCATION = Regex("""\((\d+):(\d+)\)""")
    }
}
