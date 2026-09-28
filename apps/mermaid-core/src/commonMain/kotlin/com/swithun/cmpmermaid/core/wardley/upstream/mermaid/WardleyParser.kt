package com.swithun.cmpmermaid.core.wardley.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/wardley/wardley.langium,
 * packages/parser/src/language/wardley/valueConverter.ts, and
 * packages/mermaid/src/diagrams/wardley/wardleyParser.ts -> populateDb.
 */
internal class WardleyParser(
    private val options: MermaidRenderOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<WardleyDocument, MermaidError> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val builder = WardleyBuilder()
        var title = diagramTitle
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null
        var edgeCount = 0
        var lineIndex = firstContentLine(lines)

        if (lineIndex >= lines.size || stripComment(lines[lineIndex]).trim() != HEADER) {
            return parseError(lineIndex, 1, "Expected '$HEADER' diagram header")
        }
        lineIndex += 1

        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) {
                lineIndex += 1
                continue
            }
            when {
                line.startsWith("$TITLE_KEYWORD ") -> {
                    title = line.removePrefix(TITLE_KEYWORD).trim().decodeWardleyText()
                }
                line.startsWith("$ACCESSIBILITY_TITLE_KEYWORD:") -> {
                    accessibilityTitle = line
                        .substringAfter(':')
                        .trim()
                        .decodeWardleyText()
                }
                line.startsWith("$ACCESSIBILITY_DESCRIPTION_KEYWORD:") -> {
                    accessibilityDescription = line
                        .substringAfter(':')
                        .trim()
                        .decodeWardleyText()
                }
                line == "$ACCESSIBILITY_DESCRIPTION_KEYWORD {" -> {
                    val block = parseAccessibilityBlock(lines, lineIndex)
                    when (block) {
                        is GMResult.Ok -> {
                            accessibilityDescription = block.value.value.decodeWardleyText()
                            lineIndex = block.value.endLine
                        }
                        is GMResult.Err -> return block
                    }
                }
                line.startsWith("$SIZE_KEYWORD ") -> {
                    when (val parsed = parseSize(line, lineIndex)) {
                        is GMResult.Ok -> builder.setSize(
                            width = parsed.value.width,
                            height = parsed.value.height,
                        )
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$EVOLUTION_KEYWORD ") -> {
                    when (val parsed = parseEvolution(line, lineIndex)) {
                        is GMResult.Ok -> builder.setAxes(parsed.value)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$ANCHOR_KEYWORD ") -> {
                    when (val parsed = parsePositionedNode(line, lineIndex, isAnchor = true)) {
                        is GMResult.Ok -> builder.addNode(parsed.value)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$COMPONENT_KEYWORD ") -> {
                    when (val parsed = parsePositionedNode(line, lineIndex, isAnchor = false)) {
                        is GMResult.Ok -> builder.addNode(parsed.value)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$PIPELINE_KEYWORD ") -> {
                    when (val parsed = parsePipeline(lines, lineIndex, builder)) {
                        is GMResult.Ok -> lineIndex = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$EVOLVE_KEYWORD ") -> {
                    when (val parsed = parseEvolve(line, lineIndex, builder)) {
                        is GMResult.Ok -> parsed.value?.let(builder::addTrend)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$NOTE_KEYWORD ") -> {
                    when (val parsed = parseNote(line, lineIndex)) {
                        is GMResult.Ok -> builder.addNote(parsed.value)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$ANNOTATIONS_KEYWORD ") -> {
                    when (val parsed = parseCoordinates(
                        source = line.removePrefix(ANNOTATIONS_KEYWORD).trim(),
                        lineIndex = lineIndex,
                        context = "Annotations box",
                        allowIntegers = true,
                    )) {
                        is GMResult.Ok -> builder.setAnnotationsBox(
                            parsed.value.x,
                            parsed.value.y,
                        )
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$ANNOTATION_KEYWORD ") -> {
                    when (val parsed = parseAnnotation(line, lineIndex)) {
                        is GMResult.Ok -> builder.addAnnotation(parsed.value)
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$ACCELERATOR_KEYWORD ") -> {
                    when (val parsed = parseForce(line, lineIndex, isAccelerator = true)) {
                        is GMResult.Ok -> builder.addAccelerator(
                            WardleyAccelerator(
                                name = parsed.value.name,
                                x = parsed.value.coordinates.x,
                                y = parsed.value.coordinates.y,
                            ),
                        )
                        is GMResult.Err -> return parsed
                    }
                }
                line.startsWith("$DEACCELERATOR_KEYWORD ") -> {
                    when (val parsed = parseForce(line, lineIndex, isAccelerator = false)) {
                        is GMResult.Ok -> builder.addDeaccelerator(
                            WardleyDeaccelerator(
                                name = parsed.value.name,
                                x = parsed.value.coordinates.x,
                                y = parsed.value.coordinates.y,
                            ),
                        )
                        is GMResult.Err -> return parsed
                    }
                }
                else -> {
                    when (val parsed = parseLink(line, lineIndex, builder)) {
                        is GMResult.Ok -> {
                            builder.addLink(parsed.value)
                            edgeCount += 1
                            if (edgeCount > options.maxEdges) {
                                return GMResult.Err(
                                    MermaidError.ResourceLimit(
                                        resource = "Wardley links",
                                        actual = edgeCount,
                                        maximum = options.maxEdges,
                                    ),
                                )
                            }
                        }
                        is GMResult.Err -> return parsed
                    }
                }
            }
            lineIndex += 1
        }

        return when (val built = builder.build()) {
            is GMResult.Ok -> GMResult.Ok(
                WardleyDocument(
                    data = built.value,
                    diagramTitle = title?.takeIf(String::isNotEmpty),
                    accessibilityTitle = accessibilityTitle?.takeIf(String::isNotEmpty),
                    accessibilityDescription =
                        accessibilityDescription?.takeIf(String::isNotEmpty),
                ),
            )
            is GMResult.Err -> built
        }
    }

    private fun parseSize(
        line: String,
        lineIndex: Int,
    ): GMResult<WardleySize, MermaidError> {
        val match = SIZE_PATTERN.matchEntire(line)
            ?: return parseError(lineIndex, 1, "Invalid Wardley size directive")
        return GMResult.Ok(
            WardleySize(
                width = match.groupValues[1].toFloat(),
                height = match.groupValues[2].toFloat(),
            ),
        )
    }

    private fun parseEvolution(
        line: String,
        lineIndex: Int,
    ): GMResult<WardleyAxesConfig, MermaidError> {
        val source = line.removePrefix(EVOLUTION_KEYWORD).trim()
        val stageSources = splitOutsideQuotes(source, "->")
        if (stageSources.size < 2) {
            return parseError(lineIndex, 1, "Evolution requires at least two stages")
        }
        val stages = mutableListOf<String>()
        val boundaries = mutableListOf<Float>()
        stageSources.forEach { stageSource ->
            val parsed = parseEvolutionStage(stageSource.trim(), lineIndex)
            when (parsed) {
                is GMResult.Ok -> {
                    stages += parsed.value.name
                    parsed.value.boundary?.let(boundaries::add)
                }
                is GMResult.Err -> return parsed
            }
        }
        return GMResult.Ok(
            WardleyAxesConfig(
                stages = stages,
                stageBoundaries = boundaries,
            ),
        )
    }

    private fun parseEvolutionStage(
        source: String,
        lineIndex: Int,
    ): GMResult<EvolutionStage, MermaidError> {
        val at = findOutsideQuotes(source, '@')
        val nameAndSecond: String
        val boundary: Float?
        if (at >= 0) {
            nameAndSecond = source.substring(0, at).trim()
            val boundarySource = source.substring(at + 1).trim()
            boundary = boundarySource.takeIf(DECIMAL_PATTERN::matches)?.toFloatOrNull()
                ?: return parseError(
                    lineIndex,
                    at + 2,
                    "Invalid Wardley evolution stage boundary",
                )
        } else {
            nameAndSecond = source
            boundary = null
        }
        val slash = findOutsideQuotes(nameAndSecond, '/')
        val first = if (slash >= 0) nameAndSecond.substring(0, slash) else nameAndSecond
        val second = if (slash >= 0) nameAndSecond.substring(slash + 1) else null
        val firstName = when (val parsed = parseName(first.trim(), lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val secondName = second?.let { value ->
            when (val parsed = parseName(value.trim(), lineIndex)) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
        }
        val displayName = buildString {
            append(firstName)
            secondName?.let { value ->
                append(" / ")
                append(value)
            }
        }
        return GMResult.Ok(EvolutionStage(name = displayName, boundary = boundary))
    }

    private fun parsePositionedNode(
        line: String,
        lineIndex: Int,
        isAnchor: Boolean,
    ): GMResult<WardleyNode, MermaidError> {
        val keyword = if (isAnchor) ANCHOR_KEYWORD else COMPONENT_KEYWORD
        val match = POSITIONED_NODE_PATTERN.matchEntire(line.removePrefix(keyword).trim())
            ?: return parseError(lineIndex, 1, "Invalid Wardley $keyword statement")
        val name = when (val parsed = parseName(match.groupValues[1].trim(), lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val coordinates = when (
            val parsed = toCoordinates(
                visibility = match.groupValues[2].toFloat(),
                evolution = match.groupValues[3].toFloat(),
                context = "${if (isAnchor) "Anchor" else "Component"} \"$name\"",
                lineIndex = lineIndex,
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        if (isAnchor && match.groupValues[4].isNotBlank()) {
            return parseError(lineIndex, 1, "Unexpected content after Wardley anchor")
        }
        val suffix = if (isAnchor) {
            ComponentSuffix()
        } else {
            when (val parsed = parseComponentSuffix(match.groupValues[4], lineIndex)) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
        }
        return GMResult.Ok(
            WardleyNode(
                id = name,
                label = name.decodeWardleyText(),
                x = coordinates.x,
                y = coordinates.y,
                className = if (isAnchor) "anchor" else "component",
                labelOffsetX = suffix.labelOffsetX,
                labelOffsetY = suffix.labelOffsetY,
                inertia = suffix.inertia,
                sourceStrategy = suffix.sourceStrategy,
            ),
        )
    }

    private fun parseComponentSuffix(
        source: String,
        lineIndex: Int,
    ): GMResult<ComponentSuffix, MermaidError> {
        var remaining = source.trim()
        var offsetX: Float? = null
        var offsetY: Float? = null
        var inertia = false
        var strategy: WardleySourceStrategy? = null
        while (remaining.isNotEmpty()) {
            val label = LABEL_PATTERN.find(remaining)
            if (label != null && label.range.first == 0) {
                offsetX = label.groupValues[1].toFloat()
                offsetY = label.groupValues[2].toFloat()
                remaining = remaining.substring(label.range.last + 1).trim()
                continue
            }
            val decorator = DECORATOR_PATTERN.find(remaining)
            if (decorator != null && decorator.range.first == 0) {
                when (val value = decorator.groupValues[1]) {
                    "inertia" -> inertia = true
                    "build" -> strategy = WardleySourceStrategy.Build
                    "buy" -> strategy = WardleySourceStrategy.Buy
                    "outsource" -> strategy = WardleySourceStrategy.Outsource
                    "market" -> strategy = WardleySourceStrategy.Market
                    else -> return parseError(
                        lineIndex,
                        1,
                        "Unsupported Wardley component decorator '$value'",
                    )
                }
                remaining = remaining.substring(decorator.range.last + 1).trim()
                continue
            }
            if (remaining == INERTIA_KEYWORD) {
                inertia = true
                remaining = ""
                continue
            }
            return parseError(lineIndex, 1, "Invalid Wardley component suffix '$remaining'")
        }
        return GMResult.Ok(
            ComponentSuffix(
                labelOffsetX = offsetX,
                labelOffsetY = offsetY,
                inertia = inertia,
                sourceStrategy = strategy,
            ),
        )
    }

    private fun parsePipeline(
        lines: List<String>,
        openingLine: Int,
        builder: WardleyBuilder,
    ): GMResult<Int, MermaidError> {
        val opening = stripComment(lines[openingLine]).trim()
        val match = PIPELINE_PATTERN.matchEntire(opening)
            ?: return parseError(openingLine, 1, "Invalid Wardley pipeline declaration")
        val parent = when (val parsed = parseName(match.groupValues[1].trim(), openingLine)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val parentNode = builder.getNode(parent)
        if (parentNode?.y == null) {
            return parseError(
                openingLine,
                1,
                "Pipeline \"$parent\" must reference an existing component with coordinates",
            )
        }
        builder.startPipeline(parent)
        var lineIndex = openingLine + 1
        while (lineIndex < lines.size) {
            val line = stripComment(lines[lineIndex]).trim()
            if (line.isEmpty()) {
                lineIndex += 1
                continue
            }
            if (line == "}") {
                return GMResult.Ok(lineIndex)
            }
            val component = PIPELINE_COMPONENT_PATTERN.matchEntire(line)
                ?: return parseError(
                    lineIndex,
                    1,
                    "Expected a pipeline component or closing brace",
                )
            val name = when (
                val parsed = parseName(component.groupValues[1].trim(), lineIndex)
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val x = when (
                val parsed = toPercent(
                    value = component.groupValues[2].toFloat(),
                    context = "Pipeline component \"$name\" evolution",
                    lineIndex = lineIndex,
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val suffix = when (
                val parsed = parsePipelineComponentSuffix(
                    component.groupValues[3],
                    lineIndex,
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val componentId = "${parent}_$name"
            builder.addNode(
                WardleyNode(
                    id = componentId,
                    label = name.decodeWardleyText(),
                    x = x,
                    y = parentNode.y,
                    className = "pipeline-component",
                    labelOffsetX = suffix.first,
                    labelOffsetY = suffix.second,
                ),
            )
            builder.addPipelineComponent(parent, componentId)
            lineIndex += 1
        }
        return parseError(openingLine, 1, "Unterminated Wardley pipeline '$parent'")
    }

    private fun parsePipelineComponentSuffix(
        source: String,
        lineIndex: Int,
    ): GMResult<Pair<Float?, Float?>, MermaidError> {
        val remaining = source.trim()
        if (remaining.isEmpty()) {
            return GMResult.Ok(null to null)
        }
        val match = LABEL_PATTERN.matchEntire(remaining)
            ?: return parseError(lineIndex, 1, "Invalid Wardley pipeline component label")
        return GMResult.Ok(
            match.groupValues[1].toFloat() to match.groupValues[2].toFloat(),
        )
    }

    private fun parseEvolve(
        line: String,
        lineIndex: Int,
        builder: WardleyBuilder,
    ): GMResult<WardleyTrend?, MermaidError> {
        val match = EVOLVE_PATTERN.matchEntire(line.removePrefix(EVOLVE_KEYWORD).trim())
            ?: return parseError(lineIndex, 1, "Invalid Wardley evolve statement")
        val name = when (val parsed = parseName(match.groupValues[1].trim(), lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val node = builder.getNode(name)
        if (node?.y == null) {
            return GMResult.Ok(null)
        }
        return when (
            val target = toPercent(
                match.groupValues[2].toFloat(),
                "Evolve target for \"$name\"",
                lineIndex,
            )
        ) {
            is GMResult.Ok -> GMResult.Ok(
                WardleyTrend(
                    nodeId = name,
                    targetX = target.value,
                    targetY = node.y,
                ),
            )
            is GMResult.Err -> target
        }
    }

    private fun parseNote(
        line: String,
        lineIndex: Int,
    ): GMResult<WardleyNote, MermaidError> {
        val source = line.removePrefix(NOTE_KEYWORD).trim()
        val quoted = when (val parsed = parseLeadingQuoted(source, lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val coordinates = when (
            val parsed = parseCoordinates(
                quoted.remaining.trim(),
                lineIndex,
                "Note \"${quoted.value}\"",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        return GMResult.Ok(
            WardleyNote(
                text = quoted.value.decodeWardleyText(),
                x = coordinates.x,
                y = coordinates.y,
            ),
        )
    }

    private fun parseAnnotation(
        line: String,
        lineIndex: Int,
    ): GMResult<WardleyAnnotation, MermaidError> {
        val match = ANNOTATION_PREFIX_PATTERN.matchEntire(
            line.removePrefix(ANNOTATION_KEYWORD).trim(),
        ) ?: return parseError(lineIndex, 1, "Invalid Wardley annotation")
        val number = match.groupValues[1].toInt()
        val coordinates = when (
            val parsed = toCoordinates(
                visibility = match.groupValues[2].toFloat(),
                evolution = match.groupValues[3].toFloat(),
                context = "Annotation $number",
                lineIndex = lineIndex,
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val text = when (val parsed = parseLeadingQuoted(match.groupValues[4].trim(), lineIndex)) {
            is GMResult.Ok -> {
                if (parsed.value.remaining.isNotBlank()) {
                    return parseError(lineIndex, 1, "Unexpected content after Wardley annotation")
                }
                parsed.value.value.decodeWardleyText()
            }
            is GMResult.Err -> return parsed
        }
        return GMResult.Ok(
            WardleyAnnotation(
                number = number,
                coordinates = listOf(coordinates),
                text = text,
            ),
        )
    }

    private fun parseForce(
        line: String,
        lineIndex: Int,
        isAccelerator: Boolean,
    ): GMResult<ParsedForce, MermaidError> {
        val keyword = if (isAccelerator) ACCELERATOR_KEYWORD else DEACCELERATOR_KEYWORD
        val match = FORCE_PATTERN.matchEntire(line.removePrefix(keyword).trim())
            ?: return parseError(lineIndex, 1, "Invalid Wardley $keyword statement")
        val name = when (val parsed = parseName(match.groupValues[1].trim(), lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val coordinates = when (
            val parsed = toCoordinates(
                visibility = match.groupValues[2].toFloat(),
                evolution = match.groupValues[3].toFloat(),
                context = "${if (isAccelerator) "Accelerator" else "Deaccelerator"} \"$name\"",
                lineIndex = lineIndex,
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        return GMResult.Ok(
            ParsedForce(
                name = name.decodeWardleyText(),
                coordinates = coordinates,
            ),
        )
    }

    private fun parseCoordinates(
        source: String,
        lineIndex: Int,
        context: String,
        allowIntegers: Boolean = false,
    ): GMResult<WardleyCoordinate, MermaidError> {
        val pattern = if (allowIntegers) COORDINATE_PATTERN else DECIMAL_COORDINATE_PATTERN
        val match = pattern.matchEntire(source)
            ?: return parseError(lineIndex, 1, "Invalid $context coordinates")
        return toCoordinates(
            visibility = match.groupValues[1].toFloat(),
            evolution = match.groupValues[2].toFloat(),
            context = context,
            lineIndex = lineIndex,
        )
    }

    private fun parseLink(
        line: String,
        lineIndex: Int,
        builder: WardleyBuilder,
    ): GMResult<WardleyLink, MermaidError> {
        val semicolon = findOutsideQuotes(line, ';')
        val statement = if (semicolon >= 0) line.substring(0, semicolon).trim() else line
        val annotation = if (semicolon >= 0) {
            line.substring(semicolon + 1).trim().takeIf(String::isNotEmpty)
        } else {
            null
        }
        val operator = findLinkOperator(statement)
            ?: return parseError(lineIndex, 1, "Expected a Wardley statement or dependency")
        val from = when (
            val parsed = parseName(statement.substring(0, operator.start).trim(), lineIndex)
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        var targetSource = statement.substring(operator.endExclusive).trim()
        val trailingPort = FLOW_PORTS.firstOrNull { port -> targetSource.endsWith(port) }
        if (trailingPort != null) {
            targetSource = targetSource.dropLast(trailingPort.length).trim()
        }
        val target = when (val parsed = parseName(targetSource, lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val customFlow = CUSTOM_FLOW_PATTERN.matchEntire(operator.token)
        val flow = flowFromOperator(operator.token)
            ?: trailingPort?.let(::flowFromOperator)
        val flowLabel = customFlow?.groupValues?.get(1)?.decodeWardleyText()
        return GMResult.Ok(
            WardleyLink(
                source = builder.resolveNodeId(from),
                target = builder.resolveNodeId(target),
                dashed = operator.token == "-.->",
                label = flowLabel ?: annotation?.decodeWardleyText(),
                flow = flow,
            ),
        )
    }

    private fun parseName(
        source: String,
        lineIndex: Int,
    ): GMResult<String, MermaidError> {
        if (source.isEmpty()) {
            return parseError(lineIndex, 1, "Expected a Wardley name")
        }
        if (source.first() == '"' || source.first() == '\'') {
            return when (val parsed = parseLeadingQuoted(source, lineIndex)) {
                is GMResult.Ok -> if (parsed.value.remaining.isBlank()) {
                    GMResult.Ok(parsed.value.value)
                } else {
                    parseError(lineIndex, 1, "Unexpected content after quoted Wardley name")
                }
                is GMResult.Err -> parsed
            }
        }
        if (!NAME_PATTERN.matches(source)) {
            return parseError(lineIndex, 1, "Invalid Wardley name '$source'")
        }
        return GMResult.Ok(source.trim())
    }

    private fun parseLeadingQuoted(
        source: String,
        lineIndex: Int,
    ): GMResult<QuotedValue, MermaidError> {
        val quote = source.firstOrNull()
        if (quote != '"' && quote != '\'') {
            return parseError(lineIndex, 1, "Expected a quoted Wardley string")
        }
        val value = StringBuilder()
        var index = 1
        while (index < source.length) {
            val character = source[index]
            index += 1
            when {
                character == quote -> return GMResult.Ok(
                    QuotedValue(
                        value = value.toString(),
                        remaining = source.substring(index),
                    ),
                )
                character == '\\' -> {
                    val escaped = source.getOrNull(index)
                        ?: return parseError(lineIndex, index, "Unterminated quoted string")
                    index += 1
                    value.append(
                        when (escaped) {
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'v' -> '\u000B'
                            '0' -> '\u0000'
                            else -> escaped
                        },
                    )
                }
                else -> value.append(character)
            }
        }
        return parseError(lineIndex, 1, "Unterminated quoted Wardley string")
    }

    /**
     * Mermaid.js 12.0.0: wardleyParser.ts -> toPercent.
     */
    private fun toPercent(
        value: Float,
        context: String,
        lineIndex: Int,
    ): GMResult<Float, MermaidError> {
        val normalized = if (value <= 1f) value * 100f else value
        if (!normalized.isFinite() || normalized !in 0f..100f) {
            return parseError(
                lineIndex,
                1,
                "$context must be between 0-1 (decimal) or 0-100 (percentage). " +
                    "Received: $value",
            )
        }
        return GMResult.Ok(normalized)
    }

    /**
     * Mermaid.js 12.0.0: wardleyParser.ts -> toCoordinates.
     */
    private fun toCoordinates(
        visibility: Float,
        evolution: Float,
        context: String,
        lineIndex: Int,
    ): GMResult<WardleyCoordinate, MermaidError> {
        val x = when (val parsed = toPercent(evolution, "$context evolution", lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val y = when (val parsed = toPercent(visibility, "$context visibility", lineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        return GMResult.Ok(WardleyCoordinate(x = x, y = y))
    }

    private fun parseAccessibilityBlock(
        lines: List<String>,
        openingLine: Int,
    ): GMResult<ParsedBlock, MermaidError> {
        val content = mutableListOf<String>()
        var lineIndex = openingLine + 1
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val closing = line.indexOf('}')
            if (closing >= 0) {
                val prefix = line.substring(0, closing).trim()
                if (prefix.isNotEmpty()) {
                    content += prefix
                }
                if (stripComment(line.substring(closing + 1)).isNotBlank()) {
                    return parseError(
                        lineIndex,
                        closing + 2,
                        "Unexpected content after accDescr block",
                    )
                }
                return GMResult.Ok(
                    ParsedBlock(
                        value = content.joinToString("\n") { value ->
                            value.trim().replace(HORIZONTAL_SPACES, " ")
                        }.trim(),
                        endLine = lineIndex,
                    ),
                )
            }
            content += line.trim()
            lineIndex += 1
        }
        return parseError(openingLine, 1, "Unterminated accDescr block")
    }

    private fun parseError(
        lineIndex: Int,
        column: Int,
        detail: String,
    ): GMResult.Err<MermaidError> {
        val line = lineOffset + lineIndex + 1
        return GMResult.Err(
            MermaidError.Parse(
                line = line,
                column = column,
                message = "Parse error on line $line, column $column: $detail",
            ),
        )
    }

    private data class ComponentSuffix(
        val labelOffsetX: Float? = null,
        val labelOffsetY: Float? = null,
        val inertia: Boolean = false,
        val sourceStrategy: WardleySourceStrategy? = null,
    )

    private data class EvolutionStage(
        val name: String,
        val boundary: Float?,
    )

    private data class ParsedForce(
        val name: String,
        val coordinates: WardleyCoordinate,
    )

    private data class ParsedBlock(
        val value: String,
        val endLine: Int,
    )

    private data class QuotedValue(
        val value: String,
        val remaining: String,
    )

    private data class LinkOperator(
        val token: String,
        val start: Int,
        val endExclusive: Int,
    )

    private companion object {
        const val HEADER = "wardley-beta"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        const val SIZE_KEYWORD = "size"
        const val EVOLUTION_KEYWORD = "evolution"
        const val ANCHOR_KEYWORD = "anchor"
        const val COMPONENT_KEYWORD = "component"
        const val PIPELINE_KEYWORD = "pipeline"
        const val EVOLVE_KEYWORD = "evolve"
        const val NOTE_KEYWORD = "note"
        const val ANNOTATIONS_KEYWORD = "annotations"
        const val ANNOTATION_KEYWORD = "annotation"
        const val ACCELERATOR_KEYWORD = "accelerator"
        const val DEACCELERATOR_KEYWORD = "deaccelerator"
        const val INERTIA_KEYWORD = "inertia"

        const val DECIMAL = """[0-9]+\.[0-9]+"""
        const val INTEGER = """[0-9]+"""
        const val SIGNED_INTEGER = """-?[0-9]+"""

        val DECIMAL_PATTERN = Regex("^$DECIMAL$")
        val SIZE_PATTERN = Regex("""^size\s*\[\s*($INTEGER)\s*,\s*($INTEGER)\s*]\s*$""")
        val POSITIONED_NODE_PATTERN = Regex(
            """^(.+?)\s*\[\s*($DECIMAL)\s*,\s*($DECIMAL)\s*]\s*(.*)$""",
        )
        val PIPELINE_PATTERN = Regex("""^pipeline\s+(.+?)\s*\{\s*$""")
        val PIPELINE_COMPONENT_PATTERN = Regex(
            """^component\s+(.+?)\s*\[\s*($DECIMAL)\s*]\s*(.*)$""",
        )
        val LABEL_PATTERN = Regex(
            """^label\s*\[\s*($SIGNED_INTEGER)\s*,\s*($SIGNED_INTEGER)\s*]""",
        )
        val DECORATOR_PATTERN = Regex(
            """^\(\s*(build|buy|outsource|market|inertia)\s*\)""",
        )
        val EVOLVE_PATTERN = Regex("""^(.+?)\s+($DECIMAL)\s*$""")
        val COORDINATE_PATTERN = Regex(
            """^\[\s*($DECIMAL|$INTEGER)\s*,\s*($DECIMAL|$INTEGER)\s*]\s*$""",
        )
        val DECIMAL_COORDINATE_PATTERN = Regex(
            """^\[\s*($DECIMAL)\s*,\s*($DECIMAL)\s*]\s*$""",
        )
        val ANNOTATION_PREFIX_PATTERN = Regex(
            """^($INTEGER)\s*,\s*\[\s*($DECIMAL|$INTEGER)\s*,\s*""" +
                """($DECIMAL|$INTEGER)\s*]\s*(.+)$""",
        )
        val FORCE_PATTERN = Regex(
            """^(.+?)\s*\[\s*($DECIMAL)\s*,\s*($DECIMAL)\s*]\s*$""",
        )
        val NAME_PATTERN = Regex(
            """^[A-Za-z](?:[A-Za-z0-9_()&]|-(?!>))*""" +
                """(?:[ \t]+[A-Za-z(](?:[A-Za-z0-9_()&]|-(?!>))*)*$""",
        )
        val CUSTOM_FLOW_PATTERN = Regex("""^\+'([^']*)'(< >|<>|<|>)$""".replace(" ", ""))
        val HORIZONTAL_SPACES = Regex("""[\t ]{2,}""")
        val FLOW_PORTS = listOf("+<>", "+>", "+<")
        val SIMPLE_LINK_OPERATORS = listOf("-.->", "-->", "->", "+<>", "+>", "+<", ">")

        fun firstContentLine(lines: List<String>): Int {
            var index = 0
            while (index < lines.size) {
                val line = stripComment(lines[index]).trim()
                if (line.isNotEmpty()) {
                    return index
                }
                index += 1
            }
            return index
        }

        fun stripComment(line: String): String {
            var quote: Char? = null
            var escaped = false
            var index = 0
            while (index < line.length - 1) {
                val character = line[index]
                when {
                    escaped -> escaped = false
                    character == '\\' && quote != null -> escaped = true
                    quote != null && character == quote -> quote = null
                    quote == null && (character == '"' || character == '\'') -> quote = character
                    quote == null && character == '%' && line[index + 1] == '%' ->
                        return line.substring(0, index)
                }
                index += 1
            }
            return line
        }

        fun splitOutsideQuotes(
            source: String,
            delimiter: String,
        ): List<String> {
            val result = mutableListOf<String>()
            var quote: Char? = null
            var escaped = false
            var start = 0
            var index = 0
            while (index <= source.length - delimiter.length) {
                val character = source[index]
                when {
                    escaped -> escaped = false
                    character == '\\' && quote != null -> escaped = true
                    quote != null && character == quote -> quote = null
                    quote == null && (character == '"' || character == '\'') -> quote = character
                    quote == null && source.startsWith(delimiter, index) -> {
                        result += source.substring(start, index)
                        index += delimiter.length
                        start = index
                        continue
                    }
                }
                index += 1
            }
            result += source.substring(start)
            return result
        }

        fun findOutsideQuotes(
            source: String,
            character: Char,
        ): Int {
            var quote: Char? = null
            var escaped = false
            source.forEachIndexed { index, current ->
                when {
                    escaped -> escaped = false
                    current == '\\' && quote != null -> escaped = true
                    quote != null && current == quote -> quote = null
                    quote == null && (current == '"' || current == '\'') -> quote = current
                    quote == null && current == character -> return index
                }
            }
            return -1
        }

        fun findLinkOperator(source: String): LinkOperator? {
            var quote: Char? = null
            var escaped = false
            var index = 0
            while (index < source.length) {
                val character = source[index]
                when {
                    escaped -> escaped = false
                    character == '\\' && quote != null -> escaped = true
                    quote != null && character == quote -> quote = null
                    quote == null && character == '\'' -> quote = character
                    quote == null && character == '"' -> quote = character
                    quote == null && source.startsWith("+'", index) -> {
                        val closeQuote = source.indexOf('\'', startIndex = index + 2)
                        if (closeQuote > index + 1) {
                            val suffix = FLOW_PORTS
                                .map { value -> value.drop(1) }
                                .firstOrNull { value ->
                                    source.startsWith(value, closeQuote + 1)
                                }
                            if (suffix != null) {
                                val end = closeQuote + 1 + suffix.length
                                return LinkOperator(source.substring(index, end), index, end)
                            }
                        }
                    }
                    quote == null -> SIMPLE_LINK_OPERATORS.firstOrNull { operator ->
                        source.startsWith(operator, index)
                    }?.let { operator ->
                        return LinkOperator(operator, index, index + operator.length)
                    }
                }
                index += 1
            }
            return null
        }

        fun flowFromOperator(operator: String): WardleyFlow? = when {
            operator == "+<>" || operator.endsWith("<>") -> WardleyFlow.Bidirectional
            operator == "+<" || operator.endsWith("<") -> WardleyFlow.Backward
            operator == "+>" || operator.startsWith("+'") && operator.endsWith(">") ->
                WardleyFlow.Forward
            else -> null
        }
    }
}
