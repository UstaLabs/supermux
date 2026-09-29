package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.JisonCell

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid.js 12.0.0
 * agentflow.jison semantic actions.
 */
internal class AgentflowJisonParser(
    private val config: MermaidRenderOptions = MermaidRenderOptions(),
    private val diagramTitle: String? = null,
    private val frontmatterLineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<AgentflowDb, MermaidError> {
        val db = AgentflowDb(config, frontmatterLineOffset)
        diagramTitle?.let(db::setDiagramTitle)
        val normalizedSource = source.replace(TRAILING_METADATA_WHITESPACE, "}\n")
        val lexer = AgentflowJisonLexer(normalizedSource, db::firstGraph)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        val locations = mutableListOf<AgentflowJisonLocation?>(null)
        var lookahead: AgentflowJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = AgentflowJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is JisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    locations += token.location
                    lookahead = null
                }
                is JisonCell.Reduce -> {
                    val production = AgentflowJisonTables.productions.getOrNull(action.production)
                        ?: return parseError(token, "Unknown production ${action.production}")
                    if (
                        production.length >= states.size ||
                        production.length >= values.size ||
                        production.length >= locations.size
                    ) {
                        return parseError(
                            token,
                            "Invalid parser stack for production ${action.production}",
                        )
                    }
                    val start = values.size - production.length
                    val rightHandSide = values.subList(start, values.size).toList()
                    val rightHandLocations = locations.subList(start, locations.size).toList()
                    val reducedLocation = reducedLocation(rightHandLocations, token.location)
                    val reduced = when (
                        val result = reduce(
                            production = action.production,
                            values = rightHandSide,
                            locations = rightHandLocations,
                            reducedLocation = reducedLocation,
                            db = db,
                            token = token,
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    repeat(production.length) {
                        states.removeAt(states.lastIndex)
                        values.removeAt(values.lastIndex)
                        locations.removeAt(locations.lastIndex)
                    }
                    val goto = AgentflowJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? JisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                    locations += reducedLocation
                }
                JisonCell.Accept -> return GMResult.Ok(db)
                is JisonCell.Goto -> return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        locations: List<AgentflowJisonLocation?>,
        reducedLocation: AgentflowJisonLocation,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        fun value(index: Int): Any? = values.getOrNull(index)
        fun location(index: Int): AgentflowJisonLocation =
            locations.getOrNull(index) ?: reducedLocation
        fun string(index: Int): GMResult<String, MermaidError> =
            (value(index) as? String)
                ?.let(::ok)
                ?: semanticError(token, production, "string", index)
        fun text(index: Int): GMResult<AgentflowText, MermaidError> =
            (value(index) as? AgentflowText)
                ?.let(::ok)
                ?: semanticError(token, production, "AgentflowText", index)
        fun nodes(index: Int): GMResult<NodeIds, MermaidError> =
            (value(index) as? NodeIds)
                ?.let(::ok)
                ?: semanticError(token, production, "node list", index)
        fun vertexStatement(index: Int): GMResult<VertexStatement, MermaidError> =
            (value(index) as? VertexStatement)
                ?.let(::ok)
                ?: semanticError(token, production, "vertex statement", index)
        fun link(index: Int): GMResult<AgentflowLink, MermaidError> =
            (value(index) as? AgentflowLink)
                ?.let(::ok)
                ?: semanticError(token, production, "link", index)
        fun document(index: Int): GMResult<MutableList<AgentflowDocumentItem>, MermaidError> {
            val raw = value(index)
            if (raw !is MutableList<*> || raw.any { it !is AgentflowDocumentItem }) {
                return semanticError(token, production, "document", index)
            }
            return ok(raw.filterIsInstance<AgentflowDocumentItem>().toMutableList())
        }
        fun styles(index: Int): GMResult<StyleList, MermaidError> =
            (value(index) as? StyleList)
                ?.let(::ok)
                ?: semanticError(token, production, "style list", index)
        fun positions(index: Int): GMResult<LinkPositions, MermaidError> =
            (value(index) as? LinkPositions)
                ?.let(::ok)
                ?: semanticError(token, production, "link positions", index)

        return when (production) {
            2 -> ok(mutableListOf<AgentflowDocumentItem>())
            3 -> {
                val target = when (val parsed = document(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (val statement = value(1)) {
                    is NodeIds -> {
                        if (statement.values.isNotEmpty()) {
                            target += AgentflowDocumentItem.Nodes(statement.values)
                        }
                    }
                    is SubgraphValue -> target += AgentflowDocumentItem.Subgraph(statement.id)
                    is AgentflowDocumentItem.Direction -> target += statement
                    else -> Unit
                }
                ok(target)
            }
            4, 198 -> ok(value(0))
            5, 33, 34, 35, 36, 37 -> ok(EmptyStatement)
            13 -> {
                db.setDirection("TB")
                ok("TB")
            }
            14 -> when (val direction = string(1)) {
                is GMResult.Ok -> {
                    db.setDirection(direction.value)
                    ok(direction.value)
                }
                is GMResult.Err -> direction
            }
            32 -> when (val statement = vertexStatement(0)) {
                is GMResult.Ok -> ok(NodeIds(statement.value.nodes))
                is GMResult.Err -> statement
            }
            in 38..42 -> reduceSubgraph(
                production,
                values,
                locations,
                db,
                token,
            )
            43 -> {
                val body = when (val parsed = document(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                db.addGlobal(body)
                ok(EmptyStatement)
            }
            in 44..47 -> reduceConnector(
                production,
                values,
                locations,
                db,
                token,
            )
            49 -> when (val title = string(1)) {
                is GMResult.Ok -> {
                    db.setAccessibilityTitle(title.value)
                    ok(EmptyStatement)
                }
                is GMResult.Err -> title
            }
            50, 51 -> when (val description = string(values.lastIndex)) {
                is GMResult.Ok -> {
                    db.setAccessibilityDescription(description.value)
                    ok(EmptyStatement)
                }
                is GMResult.Err -> description
            }
            56 -> concatenateStrings(values, token, production)
            57 -> ok(value(0))
            58 -> {
                val previous = when (val parsed = vertexStatement(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val edge = when (val parsed = link(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val next = when (val parsed = nodes(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val metadata = when (val parsed = string(3)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (
                    val added = db.addVertex(
                        id = next.values.last(),
                        metadata = metadata,
                        metadataLocation = location(3),
                    )
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return added
                }
                db.extendVertexMapping(next.values.last(), location(3))
                when (val added = db.addLink(previous.statement, next.values, edge)) {
                    is GMResult.Ok -> {
                        db.addEdgeMapping(next.values, reducedLocation)
                        ok(VertexStatement(next.values, next.values + previous.nodes))
                    }
                    is GMResult.Err -> added
                }
            }
            59, 60 -> {
                val previous = when (val parsed = vertexStatement(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val edge = when (val parsed = link(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val nextIndex = if (production == 59) 2 else 2
                val next = when (val parsed = nodes(nextIndex)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (val added = db.addLink(previous.statement, next.values, edge)) {
                    is GMResult.Ok -> {
                        db.addEdgeMapping(next.values, reducedLocation)
                        ok(VertexStatement(next.values, next.values + previous.nodes))
                    }
                    is GMResult.Err -> added
                }
            }
            61 -> when (val parsed = nodes(0)) {
                is GMResult.Ok -> ok(VertexStatement(parsed.value.values, parsed.value.values))
                is GMResult.Err -> parsed
            }
            62 -> {
                val parsedNodes = when (val parsed = nodes(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val metadata = when (val parsed = string(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (
                    val added = db.addVertex(
                        id = parsedNodes.values.last(),
                        metadata = metadata,
                        metadataLocation = location(1),
                    )
                ) {
                    is GMResult.Ok -> {
                        db.extendVertexMapping(parsedNodes.values.last(), location(1))
                        ok(VertexStatement(parsedNodes.values, parsedNodes.values))
                    }
                    is GMResult.Err -> added
                }
            }
            63 -> when (val parsed = nodes(0)) {
                is GMResult.Ok -> ok(VertexStatement(parsed.value.values, parsed.value.values))
                is GMResult.Err -> parsed
            }
            64 -> when (val id = string(0)) {
                is GMResult.Ok -> ok(NodeIds(listOf(id.value)))
                is GMResult.Err -> id
            }
            65 -> {
                val existing = when (val parsed = nodes(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val metadata = when (val parsed = string(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val next = when (val parsed = string(5)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (
                    val added = db.addVertex(
                        id = existing.values.last(),
                        metadata = metadata,
                        metadataLocation = location(1),
                    )
                ) {
                    is GMResult.Ok -> {
                        db.extendVertexMapping(existing.values.last(), location(1))
                        ok(NodeIds(existing.values + next))
                    }
                    is GMResult.Err -> added
                }
            }
            66 -> {
                val existing = when (val parsed = nodes(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val next = when (val parsed = string(4)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                ok(NodeIds(existing.values + next))
            }
            67 -> ok(value(0))
            68 -> {
                val id = when (val parsed = string(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val className = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                db.setClass(id, className)
                ok(id)
            }
            in 69..85 -> reduceVertex(
                production,
                values,
                reducedLocation,
                db,
                token,
            )
            86 -> {
                val edge = when (val parsed = link(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val label = when (val parsed = text(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                edge.text = label
                ok(edge)
            }
            87, 88 -> {
                val edge = when (val parsed = link(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val label = when (val parsed = text(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                edge.text = label
                ok(edge)
            }
            89 -> ok(value(0))
            90, 91 -> reduceLabeledLink(production, values, db, token)
            92 -> flowText(values, 0, FlowLabelType.Text, token, production)
            93 -> concatenateText(values, token, production)
            94 -> flowText(values, 0, FlowLabelType.String, token, production)
            95 -> flowText(values, 0, FlowLabelType.Markdown, token, production)
            96, 97 -> reduceLinkStatement(production, values, db, token)
            98 -> ok(value(1))
            99 -> flowText(values, 0, FlowLabelType.Text, token, production)
            100 -> concatenateText(values, token, production)
            101 -> flowText(values, 0, FlowLabelType.String, token, production)
            102 -> flowText(values, 0, FlowLabelType.Markdown, token, production)
            116, 118 -> flowText(values, 0, FlowLabelType.Text, token, production)
            117 -> concatenateText(values, token, production)
            119 -> flowText(values, 0, FlowLabelType.Markdown, token, production)
            120 -> {
                val ids = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val parsedStyles = when (val parsed = styles(4)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                db.addClass(ids, parsedStyles.values)
                ok(EmptyStatement)
            }
            121 -> {
                val ids = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val className = when (val parsed = string(4)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                db.setClass(ids, className)
                ok(EmptyStatement)
            }
            in 122..135 -> reduceClick(production, values, db, token)
            136 -> {
                val id = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val parsedStyles = when (val parsed = styles(4)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (val added = db.addVertex(id, style = parsedStyles.values)) {
                    is GMResult.Ok -> ok(EmptyStatement)
                    is GMResult.Err -> added
                }
            }
            in 137..142 -> reduceLinkStyle(production, values, db, token)
            143 -> when (val raw = string(0)) {
                is GMResult.Ok -> {
                    val index = raw.value.toIntOrNull()
                        ?: return semanticError(token, production, "link index", 0)
                    ok(LinkPositions(mutableListOf(AgentflowDb.LinkPosition.Index(index))))
                }
                is GMResult.Err -> raw
            }
            144 -> {
                val target = when (val parsed = positions(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val raw = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val index = raw.toIntOrNull()
                    ?: return semanticError(token, production, "link index", 2)
                target.values += AgentflowDb.LinkPosition.Index(index)
                ok(target)
            }
            145 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(StyleList(listOf(raw.value)))
                is GMResult.Err -> raw
            }
            146 -> {
                val target = when (val parsed = styles(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val raw = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                ok(StyleList(target.values + raw))
            }
            148, 197, 199 -> concatenateStrings(values, token, production)
            196 -> ok(value(0))
            200 -> ok(AgentflowDocumentItem.Direction("TB"))
            201 -> ok(AgentflowDocumentItem.Direction("BT"))
            202 -> ok(AgentflowDocumentItem.Direction("RL"))
            203 -> ok(AgentflowDocumentItem.Direction("LR"))
            204 -> ok(AgentflowDocumentItem.Direction("TD"))
            else -> ok(values.firstOrNull())
        }
    }

    private fun reduceSubgraph(
        production: Int,
        values: List<Any?>,
        locations: List<AgentflowJisonLocation?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val idIndex: Int?
        val titleIndex: Int?
        val metadataIndex: Int?
        val bodyIndex: Int
        val endIndex: Int
        when (production) {
            38 -> {
                idIndex = 2
                titleIndex = 4
                metadataIndex = null
                bodyIndex = 7
                endIndex = 8
            }
            39 -> {
                idIndex = 2
                titleIndex = 4
                metadataIndex = 6
                bodyIndex = 8
                endIndex = 9
            }
            40 -> {
                idIndex = 2
                titleIndex = null
                metadataIndex = null
                bodyIndex = 4
                endIndex = 5
            }
            41 -> {
                idIndex = 2
                titleIndex = null
                metadataIndex = 3
                bodyIndex = 5
                endIndex = 6
            }
            42 -> {
                idIndex = null
                titleIndex = null
                metadataIndex = null
                bodyIndex = 2
                endIndex = 3
            }
            else -> return semanticError(token, production, "flow production", 0)
        }
        val id = idIndex?.let { values.getOrNull(it) as? AgentflowText }
        if (idIndex != null && id == null) {
            return semanticError(token, production, "flow id", idIndex)
        }
        val title = titleIndex?.let { values.getOrNull(it) as? AgentflowText }
            ?: id?.takeIf { titleIndex == null }
        if (titleIndex != null && title == null) {
            return semanticError(token, production, "flow title", titleIndex)
        }
        val body = values.getOrNull(bodyIndex) as? MutableList<*>
            ?: return semanticError(token, production, "flow document", bodyIndex)
        if (body.any { it !is AgentflowDocumentItem }) {
            return semanticError(token, production, "flow document item", bodyIndex)
        }
        val added = when (
            val result = db.addSubgraph(
                idValue = id,
                items = body.filterIsInstance<AgentflowDocumentItem>(),
                titleValue = title,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        if (metadataIndex != null) {
            val metadata = values.getOrNull(metadataIndex) as? String
                ?: return semanticError(token, production, "flow metadata", metadataIndex)
            when (
                val result = db.addVertex(
                    id = added,
                    metadata = metadata,
                    metadataLocation = locations.getOrNull(metadataIndex),
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        if (id != null) {
            db.addSubgraphMapping(
                idValue = id,
                start = locations.firstOrNull() ?: token.location,
                end = locations.getOrNull(endIndex) ?: token.location,
            )
        }
        return ok(SubgraphValue(added))
    }

    private fun reduceConnector(
        production: Int,
        values: List<Any?>,
        locations: List<AgentflowJisonLocation?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val id = values.getOrNull(2) as? AgentflowText
            ?: return semanticError(token, production, "connector id", 2)
        val titleIndex = if (production == 44 || production == 45) 4 else null
        val title = titleIndex?.let { values.getOrNull(it) as? AgentflowText }
        if (titleIndex != null && title == null) {
            return semanticError(token, production, "connector title", titleIndex)
        }
        val metadataIndex = when (production) {
            45 -> 6
            47 -> 3
            else -> null
        }
        val connectorId = db.addConnector(id, title)
        if (metadataIndex != null) {
            val metadata = values.getOrNull(metadataIndex) as? String
                ?: return semanticError(token, production, "connector metadata", metadataIndex)
            when (
                val result = db.addVertex(
                    id = connectorId,
                    metadata = metadata,
                    metadataLocation = locations.getOrNull(metadataIndex),
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        db.addConnectorMapping(
            idValue = id,
            start = locations.firstOrNull() ?: token.location,
            end = locations.lastOrNull() ?: token.location,
        )
        return ok(NodeIds(listOf(connectorId)))
    }

    private fun reduceVertex(
        production: Int,
        values: List<Any?>,
        location: AgentflowJisonLocation,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val textIndex: Int?
        val type: String?
        val props: Map<String, String>?
        when (production) {
            69 -> {
                textIndex = 2
                type = "square"
                props = null
            }
            70 -> {
                textIndex = 2
                type = "doublecircle"
                props = null
            }
            71 -> {
                textIndex = 3
                type = "circle"
                props = null
            }
            72 -> {
                textIndex = 2
                type = "ellipse"
                props = null
            }
            73 -> {
                textIndex = 2
                type = "stadium"
                props = null
            }
            74 -> {
                textIndex = 2
                type = "subroutine"
                props = null
            }
            75 -> {
                textIndex = 6
                type = "rect"
                val field = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "property field", 2)
                val propertyValue = values.getOrNull(4) as? String
                    ?: return semanticError(token, production, "property value", 4)
                props = mapOf(field to propertyValue)
            }
            76 -> {
                textIndex = 2
                type = "cylinder"
                props = null
            }
            77 -> {
                textIndex = 2
                type = "round"
                props = null
            }
            78 -> {
                textIndex = 2
                type = "diamond"
                props = null
            }
            79 -> {
                textIndex = 3
                type = "hexagon"
                props = null
            }
            80 -> {
                textIndex = 2
                type = "odd"
                props = null
            }
            81 -> {
                textIndex = 2
                type = "trapezoid"
                props = null
            }
            82 -> {
                textIndex = 2
                type = "inv_trapezoid"
                props = null
            }
            83 -> {
                textIndex = 2
                type = "lean_right"
                props = null
            }
            84 -> {
                textIndex = 2
                type = "lean_left"
                props = null
            }
            85 -> {
                textIndex = null
                type = null
                props = null
            }
            else -> return semanticError(token, production, "vertex production", 0)
        }
        val id = values.firstOrNull() as? String
            ?: return semanticError(token, production, "vertex id", 0)
        val label = textIndex?.let { values.getOrNull(it) as? AgentflowText }
        if (textIndex != null && label == null) {
            return semanticError(token, production, "vertex text", textIndex)
        }
        return when (val added = db.addVertex(id, label, type, props = props)) {
            is GMResult.Ok -> {
                db.addVertexMapping(id, type, location)
                ok(id)
            }
            is GMResult.Err -> added
        }
    }

    private fun reduceLabeledLink(
        production: Int,
        values: List<Any?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val id: String?
        val start: String
        val label: AgentflowText
        val end: String
        if (production == 90) {
            id = null
            start = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "start link", 0)
            label = values.getOrNull(1) as? AgentflowText
                ?: return semanticError(token, production, "edge text", 1)
            end = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "end link", 2)
        } else {
            id = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "link id", 0)
            start = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "start link", 1)
            label = values.getOrNull(2) as? AgentflowText
                ?: return semanticError(token, production, "edge text", 2)
            end = values.getOrNull(3) as? String
                ?: return semanticError(token, production, "end link", 3)
        }
        return when (val parsed = db.destructLink(end, start)) {
            is GMResult.Ok -> ok(parsed.value.apply {
                text = label
                this.id = id
            })
            is GMResult.Err -> parsed
        }
    }

    private fun reduceLinkStatement(
        production: Int,
        values: List<Any?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val id = if (production == 97) {
            values.getOrNull(0) as? String
                ?: return semanticError(token, production, "link id", 0)
        } else {
            null
        }
        val sourceIndex = if (production == 97) 1 else 0
        val source = values.getOrNull(sourceIndex) as? String
            ?: return semanticError(token, production, "link", sourceIndex)
        return when (val parsed = db.destructLink(source)) {
            is GMResult.Ok -> ok(parsed.value.apply { this.id = id })
            is GMResult.Err -> parsed
        }
    }

    private fun reduceClick(
        production: Int,
        values: List<Any?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        fun raw(index: Int): String? = values.getOrNull(index) as? String
        when (production) {
            122, 130 -> db.setClickEvent(raw(0).orEmpty(), raw(1))
            123, 131 -> {
                db.setClickEvent(raw(0).orEmpty(), raw(1))
                db.setTooltip(raw(0).orEmpty(), raw(3))
            }
            124 -> db.setClickEvent(raw(0).orEmpty(), raw(1), raw(2))
            125 -> {
                db.setClickEvent(raw(0).orEmpty(), raw(1), raw(2))
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            126 -> db.setLink(raw(0).orEmpty(), raw(2).orEmpty())
            127 -> {
                db.setLink(raw(0).orEmpty(), raw(2).orEmpty())
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            128 -> db.setLink(raw(0).orEmpty(), raw(2).orEmpty(), raw(4))
            129 -> {
                db.setLink(raw(0).orEmpty(), raw(2).orEmpty(), raw(6))
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            132 -> db.setLink(raw(0).orEmpty(), raw(1).orEmpty())
            133 -> {
                db.setLink(raw(0).orEmpty(), raw(1).orEmpty())
                db.setTooltip(raw(0).orEmpty(), raw(3))
            }
            134 -> db.setLink(raw(0).orEmpty(), raw(1).orEmpty(), raw(3))
            135 -> {
                db.setLink(raw(0).orEmpty(), raw(1).orEmpty(), raw(5))
                db.setTooltip(raw(0).orEmpty(), raw(3))
            }
            else -> return semanticError(token, production, "click production", 0)
        }
        return ok(EmptyStatement)
    }

    private fun reduceLinkStyle(
        production: Int,
        values: List<Any?>,
        db: AgentflowDb,
        token: AgentflowJisonToken,
    ): GMResult<Any?, MermaidError> {
        val target = when (val raw = values.getOrNull(2)) {
            is LinkPositions -> raw.values
            is String -> listOf(AgentflowDb.LinkPosition.Default)
            else -> return semanticError(token, production, "link positions", 2)
        }
        when (production) {
            137, 138 -> {
                val style = values.getOrNull(4) as? StyleList
                    ?: return semanticError(token, production, "link style", 4)
                when (val updated = db.updateLink(target, style.values)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
            139, 140 -> {
                val interpolation = values.getOrNull(6) as? String
                    ?: return semanticError(token, production, "interpolation", 6)
                val style = values.getOrNull(8) as? StyleList
                    ?: return semanticError(token, production, "link style", 8)
                when (val updated = db.updateLinkInterpolate(target, interpolation)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
                when (val updated = db.updateLink(target, style.values)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
            141, 142 -> {
                val interpolation = values.getOrNull(6) as? String
                    ?: return semanticError(token, production, "interpolation", 6)
                when (val updated = db.updateLinkInterpolate(target, interpolation)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
        }
        return ok(EmptyStatement)
    }

    private fun flowText(
        values: List<Any?>,
        index: Int,
        type: FlowLabelType,
        token: AgentflowJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val source = values.getOrNull(index) as? String
            ?: return semanticError(token, production, "text", index)
        return ok(AgentflowText(source, type))
    }

    private fun concatenateText(
        values: List<Any?>,
        token: AgentflowJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(0) as? AgentflowText
            ?: return semanticError(token, production, "AgentflowText", 0)
        val second = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "text token", 1)
        return ok(first.copy(text = first.text + second))
    }

    private fun concatenateStrings(
        values: List<Any?>,
        token: AgentflowJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(0) as? String
            ?: return semanticError(token, production, "string", 0)
        val second = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "string", 1)
        return ok(first + second)
    }

    private fun reducedLocation(
        locations: List<AgentflowJisonLocation?>,
        fallback: AgentflowJisonLocation,
    ): AgentflowJisonLocation {
        val first = locations.firstOrNull() ?: fallback
        val last = locations.lastOrNull() ?: first
        return AgentflowJisonLocation(
            firstLine = first.firstLine,
            firstColumn = first.firstColumn,
            lastLine = last.lastLine,
            lastColumn = last.lastColumn,
            startIndex = first.startIndex,
            endIndex = last.endIndex,
        )
    }

    private fun expectedMessage(
        state: Int,
        token: AgentflowJisonToken,
    ): String {
        val expected = AgentflowJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(AgentflowJisonTables.terminalNames::get)
            .filterNot { it == "error" }
            .joinToString()
        return "Unexpected '${token.name}'${if (expected.isEmpty()) "" else "; expected $expected"}"
    }

    private fun <T> semanticError(
        token: AgentflowJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> =
        parseError(token, "Production $production expected $expected at value $index")

    private fun <T> parseError(
        token: AgentflowJisonToken,
        message: String,
    ): GMResult<T, MermaidError> =
        GMResult.Err(
            MermaidError.Parse(
                line = token.location.firstLine + frontmatterLineOffset,
                column = token.location.firstColumn + 1,
                message = "Parse error: $message",
            ),
        )

    private fun <T> ok(value: T): GMResult<T, MermaidError> = GMResult.Ok(value)

    private data class NodeIds(
        val values: List<String>,
    )

    private data class VertexStatement(
        val statement: List<String>,
        val nodes: List<String>,
    )

    private data class StyleList(
        val values: List<String>,
    )

    private data class LinkPositions(
        val values: MutableList<AgentflowDb.LinkPosition>,
    )

    private data class SubgraphValue(
        val id: String,
    )

    private object EmptyStatement

    private companion object {
        // Mermaid.js 12.0.0: agentflow/parser/agentflowParser.ts -> newParser.parse
        // Android ICU treats an unescaped `}` as invalid even though JavaScript accepts it.
        val TRAILING_METADATA_WHITESPACE = Regex("""\}[^\S\n]*\n""")
    }
}
