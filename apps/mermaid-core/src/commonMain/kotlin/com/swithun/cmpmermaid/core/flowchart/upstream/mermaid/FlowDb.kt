package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidSecurityLevel

/**
 * Kotlin port of packages/mermaid/src/diagrams/flowchart/flowDb.ts.
 *
 * DOM binding is represented as node metadata. Rendering and interaction are
 * handled by the platform adapter instead of D3.
 */
internal class FlowDb(
    private val config: MermaidRenderOptions = MermaidRenderOptions(),
) {
    private var vertexCounter = 0
    private val vertices = linkedMapOf<String, MermaidFlowVertex>()
    private val edges = mutableListOf<MermaidFlowEdge>()
    private val classes = linkedMapOf<String, MermaidFlowClass>()
    private val subgraphs = mutableListOf<MermaidFlowSubgraph>()
    private val subgraphLookup = linkedMapOf<String, MermaidFlowSubgraph>()
    private var defaultEdgeInterpolate: String? = null
    private var defaultEdgeStyle: List<String> = emptyList()
    private var subgraphCounter = 0
    private var firstGraphFlag = true
    private var direction: String? = null

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
        text: MermaidFlowText? = null,
        type: String? = null,
        style: List<String>? = null,
        classes: List<String>? = null,
        direction: String? = null,
        props: Map<String, String>? = null,
        metadata: String? = null,
    ): GMResult<Unit, MermaidError> {
        if (id.isBlank()) {
            return GMResult.Ok(Unit)
        }

        val metadataValues = when (val parsed = parseMetadata(metadata)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val subgraph = subgraphLookup[id]
        if (subgraph != null && metadata != null) {
            subgraph.metadata.putAll(metadataValues)
            return GMResult.Ok(Unit)
        }
        val edge = edges.firstOrNull { it.id == id }
        if (edge != null) {
            metadataValues["animate"]?.let { edge.animate = it.equals("true", ignoreCase = true) }
            metadataValues["animation"]?.let { edge.animation = it }
            metadataValues["curve"]?.let { edge.interpolate = it }
            return GMResult.Ok(Unit)
        }

        val vertex = vertices.getOrPut(id) {
            MermaidFlowVertex(
                id = id,
                domId = "flowchart-$id-$vertexCounter",
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
        classes?.let(vertex.classes::addAll)
        if (direction != null) {
            vertex.dir = direction
        }
        props?.let(vertex.props::putAll)

        val shape = metadataValues["shape"]
        if (shape != null) {
            if (shape != shape.lowercase() || '_' in shape) {
                return flowError("No such shape: $shape. Shape names should be lowercase.")
            }
            if (!FlowShapeRegistry.isValid(shape)) {
                return flowError("No such shape: $shape.")
            }
            vertex.type = shape
        }
        metadataValues["label"]?.takeIf(String::isNotEmpty)?.let {
            vertex.text = it
            vertex.labelType = labelType(metadataValues["labelType"])
        }
        metadataValues["icon"]?.takeIf(String::isNotEmpty)?.let {
            vertex.icon = it
            if (metadataValues["label"].isNullOrBlank() && vertex.text == id) {
                vertex.text = ""
            }
        }
        metadataValues["form"]?.let { vertex.form = it }
        metadataValues["pos"]?.let { vertex.position = it }
        metadataValues["img"]?.takeIf(String::isNotEmpty)?.let {
            vertex.image = it
            if (metadataValues["label"].isNullOrBlank() && vertex.text == id) {
                vertex.text = ""
            }
        }
        metadataValues["constraint"]?.let { vertex.constraint = it }
        metadataValues["w"]?.toFloatOrNull()?.let { vertex.assetWidth = it }
        metadataValues["h"]?.toFloatOrNull()?.let { vertex.assetHeight = it }
        return GMResult.Ok(Unit)
    }

    fun addLink(
        starts: List<String>,
        ends: List<String>,
        link: MermaidFlowLink,
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
        link: MermaidFlowLink,
        requestedId: String?,
    ): GMResult<Unit, MermaidError> {
        if (edges.size >= config.maxEdges) {
            return flowError(
                "Edge limit exceeded. ${edges.size} edges found, but the limit is ${config.maxEdges}.",
            )
        }
        val edge = MermaidFlowEdge(
            start = start,
            end = end,
            type = link.type,
            stroke = link.stroke,
            length = link.length?.coerceAtMost(10),
            interpolate = defaultEdgeInterpolate,
        )
        link.text?.let { text ->
            edge.text = text.text.trim().removeSurrounding("\"")
            edge.labelType = labelType(text.type.name.lowercase())
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
                        ?: return flowError(linkIndexError(position.value))
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
                        ?: return flowError(linkIndexError(position.value))
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
            val definition = classes.getOrPut(id) { MermaidFlowClass(id) }
            styles.forEach { style ->
                if ("color" in style) {
                    definition.textStyles += style.replace("fill", "bgFill")
                }
                definition.styles += style
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
        idValue: MermaidFlowText?,
        items: List<MermaidFlowDocumentItem>,
        titleValue: MermaidFlowText?,
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
                is MermaidFlowDocumentItem.Nodes -> nodes += item.ids
                is MermaidFlowDocumentItem.Subgraph -> nodes += item.id
                is MermaidFlowDocumentItem.Direction -> localDirection = item.value
                MermaidFlowDocumentItem.Empty -> Unit
            }
        }
        val direction = localDirection ?: if (config.inheritDirection) getDirection() else null
        val resolvedId = id ?: "subGraph${subgraphCounter}"
        subgraphCounter += 1
        val subgraph = MermaidFlowSubgraph(
            id = resolvedId,
            nodes = nodes.filterNot { existsInSubgraphs(it) }.toMutableList(),
            title = sourceTitle.trim(),
            direction = direction,
            labelType = labelType(titleValue?.type?.name?.lowercase()),
        )
        subgraphs += subgraph
        subgraphLookup[resolvedId] = subgraph
        return GMResult.Ok(resolvedId)
    }

    fun destructLink(
        endSource: String,
        startSource: String? = null,
    ): GMResult<MermaidFlowLink, MermaidError> {
        val end = destructEndLink(endSource)
        if (startSource == null) {
            return GMResult.Ok(end)
        }
        val start = destructStartLink(startSource)
        if (start.stroke != end.stroke) {
            return GMResult.Ok(MermaidFlowLink(type = "INVALID", stroke = "INVALID"))
        }
        start.type = if (start.type == "arrow_open") {
            end.type
        } else {
            if (start.type != end.type) {
                return GMResult.Ok(MermaidFlowLink(type = "INVALID", stroke = "INVALID"))
            }
            "double_${start.type}"
        }
        if (start.type == "double_arrow") {
            start.type = "double_arrow_point"
        }
        start.length = end.length
        return GMResult.Ok(start)
    }

    private fun destructStartLink(source: String): MermaidFlowLink {
        var value = source.trim()
        var type = "arrow_open"
        when (value.firstOrNull()) {
            '<' -> {
                type = "arrow_point"
                value = value.drop(1)
            }
            'x' -> {
                type = "arrow_cross"
                value = value.drop(1)
            }
            'o' -> {
                type = "arrow_circle"
                value = value.drop(1)
            }
        }
        val stroke = when {
            '=' in value -> "thick"
            '.' in value -> "dotted"
            else -> "normal"
        }
        return MermaidFlowLink(type = type, stroke = stroke)
    }

    private fun destructEndLink(source: String): MermaidFlowLink {
        val value = source.trim()
        var line = value.dropLast(1)
        var type = "arrow_open"
        when (value.lastOrNull()) {
            'x' -> {
                type = "arrow_cross"
                if (value.startsWith('x')) {
                    type = "double_$type"
                    line = line.drop(1)
                }
            }
            '>' -> {
                type = "arrow_point"
                if (value.startsWith('<')) {
                    type = "double_$type"
                    line = line.drop(1)
                }
            }
            'o' -> {
                type = "arrow_circle"
                if (value.startsWith('o')) {
                    type = "double_$type"
                    line = line.drop(1)
                }
            }
        }
        var stroke = "normal"
        var length = line.length - 1
        if (line.startsWith('=')) {
            stroke = "thick"
        }
        if (line.startsWith('~')) {
            stroke = "invisible"
        }
        val dots = line.count { it == '.' }
        if (dots > 0) {
            stroke = "dotted"
            length = dots
        }
        return MermaidFlowLink(type = type, stroke = stroke, length = length)
    }

    fun vertices(): Map<String, MermaidFlowVertex> = vertices

    fun edges(): List<MermaidFlowEdge> = edges

    fun classes(): Map<String, MermaidFlowClass> = classes

    fun subgraphs(): List<MermaidFlowSubgraph> = subgraphs

    fun defaultEdgeStyle(): List<String> = defaultEdgeStyle

    fun config(): MermaidRenderOptions = config

    /**
     * Kotlin port of FlowDB.getData(). DOM-only fields are retained as data so
     * the Native adapter can expose equivalent interaction APIs.
     */
    fun getData(): MermaidFlowLayoutData {
        val parentBySubgraph = linkedMapOf<String, String>()
        subgraphs.forEach { subgraph ->
            subgraph.nodes.forEach { childId ->
                if (childId in subgraphLookup) {
                    parentBySubgraph[childId] = subgraph.id
                }
            }
        }

        val childrenBySubgraph = linkedMapOf<String, MutableList<String>>()
        subgraphs.forEach { subgraph ->
            parentBySubgraph[subgraph.id]?.let { parentId ->
                childrenBySubgraph.getOrPut(parentId, ::mutableListOf) += subgraph.id
            }
        }
        val declarationIndex = linkedMapOf<String, Int>()
        var nextDeclarationIndex = 0
        fun walk(subgraphId: String) {
            declarationIndex[subgraphId] = nextDeclarationIndex
            nextDeclarationIndex += 1
            childrenBySubgraph[subgraphId].orEmpty().forEach(::walk)
        }
        subgraphs.forEach { subgraph ->
            if (subgraph.id !in parentBySubgraph) {
                walk(subgraph.id)
            }
        }

        fun isCollapsed(subgraphId: String): Boolean =
            subgraphLookup[subgraphId]?.metadata?.get("view") == "collapsed"

        fun outermostCollapsed(subgraphId: String): String? {
            var result: String? = null
            val seen = mutableSetOf<String>()
            var current: String? = subgraphId
            while (current != null && seen.add(current)) {
                if (isCollapsed(current)) {
                    result = current
                }
                current = parentBySubgraph[current]
            }
            return result
        }

        val hiddenIds = mutableSetOf<String>()
        val collapsedAncestorById = linkedMapOf<String, String>()
        subgraphs.forEach { subgraph ->
            val ancestor = outermostCollapsed(subgraph.id) ?: return@forEach
            if (subgraph.id != ancestor) {
                hiddenIds += subgraph.id
                collapsedAncestorById[subgraph.id] = ancestor
            }
            subgraph.nodes.forEach { childId ->
                if (childId != ancestor) {
                    hiddenIds += childId
                    collapsedAncestorById[childId] = ancestor
                }
            }
        }

        val parentByNode = linkedMapOf<String, String>()
        val groupIds = mutableSetOf<String>()
        subgraphs.asReversed().forEach { subgraph ->
            if (subgraph.id in hiddenIds) {
                return@forEach
            }
            if (subgraph.nodes.isNotEmpty()) {
                groupIds += subgraph.id
            }
            subgraph.nodes.forEach { childId ->
                parentByNode[childId] = subgraph.id
            }
        }

        val nodes = mutableListOf<MermaidFlowLayoutNode>()
        subgraphs.asReversed().forEach { subgraph ->
            if (subgraph.id in hiddenIds) {
                return@forEach
            }
            if (isCollapsed(subgraph.id)) {
                nodes += MermaidFlowLayoutNode(
                    id = subgraph.id,
                    label = subgraph.title,
                    labelType = subgraph.labelType,
                    parentId = parentByNode[subgraph.id],
                    padding = COLLAPSED_GROUP_PADDING,
                    minWidth = null,
                    look = config.look,
                    cssStyles = emptyList(),
                    cssCompiledStyles = compiledStyles(subgraph.classes),
                    cssClasses = subgraph.classes.joinToString(" "),
                    shape = "collapsedGroup",
                    dir = subgraph.direction,
                    isGroup = false,
                    colorIndex = declarationIndex[subgraph.id],
                )
            } else {
                nodes += MermaidFlowLayoutNode(
                    id = subgraph.id,
                    label = subgraph.title,
                    labelType = subgraph.labelType,
                    parentId = parentByNode[subgraph.id],
                    padding = COLLAPSED_GROUP_PADDING,
                    minWidth = null,
                    look = config.look,
                    cssStyles = emptyList(),
                    cssCompiledStyles = compiledStyles(subgraph.classes),
                    cssClasses = subgraph.classes.joinToString(" "),
                    shape = "rect",
                    dir = subgraph.direction,
                    isGroup = true,
                    metadata = subgraph.metadata,
                    colorIndex = declarationIndex[subgraph.id],
                )
            }
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
                    cssCompiledStyles = compiledStyles(vertex.classes),
                    cssClasses = vertex.classes.joinToString(" "),
                )
                return@forEach
            }
            nodes += MermaidFlowLayoutNode(
                id = vertex.id,
                label = vertex.text,
                labelType = vertex.labelType,
                parentId = parentByNode[vertex.id],
                padding = config.flowchartPadding.takeUnless { it == 0f } ?: 8f,
                minWidth = config.minNodeWidth,
                look = config.look,
                cssStyles = vertex.styles,
                cssCompiledStyles = compiledStyles(
                    listOf("default", "node") + vertex.classes,
                ),
                cssClasses = "default ${vertex.classes.joinToString(" ")}",
                shape = typeFromVertex(vertex, vertex.id in groupIds),
                dir = vertex.dir,
                isGroup = vertex.id in groupIds,
                link = vertex.link,
                linkTarget = vertex.linkTarget,
                tooltip = vertex.tooltip,
                callbackName = vertex.callbackName,
                callbackArgs = vertex.callbackArgs,
                icon = vertex.icon,
                form = vertex.form,
                position = vertex.position,
                image = vertex.image,
                assetWidth = vertex.assetWidth,
                assetHeight = vertex.assetHeight,
                constraint = vertex.constraint,
            )
        }

        val layoutEdges = buildList {
            edges.forEachIndexed { index, rawEdge ->
                val start = collapsedAncestorById[rawEdge.start] ?: rawEdge.start
                val end = collapsedAncestorById[rawEdge.end] ?: rawEdge.end
                if (
                    start == end &&
                    (rawEdge.start in collapsedAncestorById || rawEdge.end in collapsedAncestorById)
                ) {
                    return@forEachIndexed
                }

                val (arrowTypeStart, arrowTypeEnd) = destructEdgeType(rawEdge.type)
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
                            arrowTypeStart
                        },
                        arrowTypeEnd = if (invisible || rawEdge.type == "arrow_open") {
                            "none"
                        } else {
                            arrowTypeEnd
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

    private fun existsInSubgraphs(id: String): Boolean =
        subgraphs.any { id in it.nodes }

    private fun typeFromVertex(
        vertex: MermaidFlowVertex,
        isGroup: Boolean,
    ): String {
        if (isGroup) {
            return "rect"
        }
        if (vertex.image != null) {
            return "imageSquare"
        }
        if (vertex.icon != null) {
            return when (vertex.form) {
                "circle" -> "iconCircle"
                "square" -> "iconSquare"
                "rounded" -> "iconRounded"
                else -> "icon"
            }
        }
        return when (vertex.type) {
            null, "square" -> "squareRect"
            "round" -> "roundedRect"
            else -> vertex.type.orEmpty()
        }
    }

    private fun destructEdgeType(type: String?): Pair<String, String> {
        var arrowTypeStart = "none"
        var arrowTypeEnd = "arrow_point"
        when (type) {
            "arrow_point", "arrow_circle", "arrow_cross" -> {
                arrowTypeEnd = type
            }
            "double_arrow_point", "double_arrow_circle", "double_arrow_cross" -> {
                arrowTypeStart = type.removePrefix("double_")
                arrowTypeEnd = arrowTypeStart
            }
        }
        return arrowTypeStart to arrowTypeEnd
    }

    private fun compiledStyles(classNames: List<String>): List<String> = buildList {
        classNames.forEach { className ->
            classes[className]?.let { definition ->
                addAll(definition.styles.map(String::trim))
                addAll(definition.textStyles.map(String::trim))
            }
        }
    }

    private fun labelType(value: String?): FlowLabelType = when (value) {
        "markdown" -> FlowLabelType.Markdown
        "string" -> FlowLabelType.String
        "text" -> FlowLabelType.Text
        else -> FlowLabelType.Markdown
    }

    private fun parseMetadata(source: String?): GMResult<Map<String, String>, MermaidError> {
        if (source == null) {
            return GMResult.Ok(emptyMap())
        }
        val yamlSource = if ('\n' in source) {
            "$source\n"
        } else {
            "{\n$source\n}"
        }
        val parsed = try {
            Yaml.default.parseToYamlNode(yamlSource)
        } catch (failure: Exception) {
            return flowError(failure.message ?: "Invalid flowchart metadata")
        }
        val map = parsed as? YamlMap
            ?: return flowError("Flowchart metadata must be a map")
        return GMResult.Ok(
            buildMap {
                map.entries.forEach { (key, value) ->
                    put(
                        key.content,
                        if (value is YamlScalar) value.content else value.contentToString(),
                    )
                }
            }
        )
    }

    private fun edgeId(from: String, to: String, counter: Int): String =
        "L_${from}_${to}_$counter"

    private fun linkIndexError(index: Int): String =
        "The index $index for linkStyle is out of bounds. Valid indices are 0..${edges.lastIndex}."

    private fun <T> flowError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(1, 1, message))

    internal sealed interface LinkPosition {
        object Default : LinkPosition

        data class Index(val value: Int) : LinkPosition
    }

    private companion object {
        const val COLLAPSED_GROUP_PADDING = 8f
        const val STYLE_COMMA_SENTINEL = "\u0000comma\u0000"
    }
}
