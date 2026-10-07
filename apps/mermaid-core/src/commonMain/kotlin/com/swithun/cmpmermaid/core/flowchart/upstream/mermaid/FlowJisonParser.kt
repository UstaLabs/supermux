package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid flow.jison
 * semantic actions.
 */
internal class FlowJisonParser(
    private val config: MermaidRenderOptions = MermaidRenderOptions(),
    private val diagramTitle: String? = null,
) {
    fun parse(source: String): GMResult<FlowDb, MermaidError> {
        val db = FlowDb(config)
        diagramTitle?.let(db::setDiagramTitle)
        val normalizedSource = source.replace(TRAILING_METADATA_WHITESPACE, "}\n")
        val lexer = FlowJisonLexer(normalizedSource, db::firstGraph)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: JisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = FlowJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(
                    token = token,
                    message = expectedMessage(state, token),
                )

            when (action) {
                is JisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is JisonCell.Reduce -> {
                    val production = FlowJisonTables.productions.getOrNull(action.production)
                        ?: return parseError(token, "Unknown production ${action.production}")
                    if (production.length >= states.size || production.length >= values.size) {
                        return parseError(token, "Invalid parser stack for production ${action.production}")
                    }
                    val start = values.size - production.length
                    val rightHandSide = values.subList(start, values.size).toList()
                    val reduced = when (
                        val result = reduce(
                            production = action.production,
                            values = rightHandSide,
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
                    }
                    val goto = FlowJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? JisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                JisonCell.Accept -> return GMResult.Ok(db)
                is JisonCell.Goto -> return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        fun value(index: Int): Any? = values.getOrNull(index)
        fun string(index: Int): GMResult<String, MermaidError> =
            (value(index) as? String)
                ?.let(::ok)
                ?: semanticError(token, production, "string", index)
        fun text(index: Int): GMResult<MermaidFlowText, MermaidError> =
            (value(index) as? MermaidFlowText)
                ?.let(::ok)
                ?: semanticError(token, production, "FlowText", index)
        fun nodes(index: Int): GMResult<NodeIds, MermaidError> =
            (value(index) as? NodeIds)
                ?.let(::ok)
                ?: semanticError(token, production, "node list", index)
        fun vertexStatement(index: Int): GMResult<VertexStatement, MermaidError> =
            (value(index) as? VertexStatement)
                ?.let(::ok)
                ?: semanticError(token, production, "vertex statement", index)
        fun link(index: Int): GMResult<MermaidFlowLink, MermaidError> =
            (value(index) as? MermaidFlowLink)
                ?.let(::ok)
                ?: semanticError(token, production, "link", index)
        fun document(index: Int): GMResult<MutableList<MermaidFlowDocumentItem>, MermaidError> {
            val raw = value(index)
            if (raw !is MutableList<*>) {
                return semanticError(token, production, "document", index)
            }
            if (raw.any { it !is MermaidFlowDocumentItem }) {
                return semanticError(token, production, "document item", index)
            }
            return ok(raw.filterIsInstance<MermaidFlowDocumentItem>().toMutableList())
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
            2 -> ok(mutableListOf<MermaidFlowDocumentItem>())
            3 -> {
                val target = when (val parsed = document(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (val statement = value(1)) {
                    is NodeIds -> {
                        if (statement.values.isNotEmpty()) {
                            target += MermaidFlowDocumentItem.Nodes(statement.values)
                        }
                    }
                    is SubgraphValue -> target += MermaidFlowDocumentItem.Subgraph(statement.id)
                    is MermaidFlowDocumentItem.Direction -> target += statement
                    else -> Unit
                }
                ok(target)
            }
            11 -> {
                db.setDirection("TB")
                ok("TB")
            }
            12 -> when (val direction = string(1)) {
                is GMResult.Ok -> {
                    db.setDirection(direction.value)
                    ok(direction.value)
                }
                is GMResult.Err -> direction
            }
            27 -> when (val statement = vertexStatement(0)) {
                is GMResult.Ok -> ok(NodeIds(statement.value.nodes))
                is GMResult.Err -> statement
            }
            in 28..32 -> ok(EmptyStatement)
            33 -> addSubgraph(
                db = db,
                idValue = value(2),
                bodyValue = value(7),
                titleValue = value(4),
                token = token,
                production = production,
            )
            34 -> addSubgraph(
                db = db,
                idValue = value(2),
                bodyValue = value(4),
                titleValue = value(2),
                token = token,
                production = production,
            )
            35 -> addSubgraph(
                db = db,
                idValue = null,
                bodyValue = value(2),
                titleValue = null,
                token = token,
                production = production,
            )
            37 -> when (val title = string(1)) {
                is GMResult.Ok -> {
                    db.setAccessibilityTitle(title.value)
                    ok(EmptyStatement)
                }
                is GMResult.Err -> title
            }
            38, 39 -> when (val description = string(values.lastIndex)) {
                is GMResult.Ok -> {
                    db.setAccessibilityDescription(description.value)
                    ok(EmptyStatement)
                }
                is GMResult.Err -> description
            }
            43 -> concatenateStrings(values, token, production)
            44 -> ok(value(0))
            45 -> {
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
                when (val added = db.addVertex(next.values.last(), metadata = metadata)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return added
                }
                when (val added = db.addLink(previous.statement, next.values, edge)) {
                    is GMResult.Ok -> ok(
                        VertexStatement(
                            statement = next.values,
                            nodes = next.values + previous.nodes,
                        ),
                    )
                    is GMResult.Err -> added
                }
            }
            46, 47 -> {
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
                when (val added = db.addLink(previous.statement, next.values, edge)) {
                    is GMResult.Ok -> ok(
                        VertexStatement(
                            statement = next.values,
                            nodes = next.values + previous.nodes,
                        ),
                    )
                    is GMResult.Err -> added
                }
            }
            48 -> when (val parsed = nodes(0)) {
                is GMResult.Ok -> ok(VertexStatement(parsed.value.values, parsed.value.values))
                is GMResult.Err -> parsed
            }
            49 -> {
                val parsedNodes = when (val parsed = nodes(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val metadata = when (val parsed = string(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                when (val added = db.addVertex(parsedNodes.values.last(), metadata = metadata)) {
                    is GMResult.Ok -> ok(VertexStatement(parsedNodes.values, parsedNodes.values))
                    is GMResult.Err -> added
                }
            }
            50 -> when (val parsed = nodes(0)) {
                is GMResult.Ok -> ok(VertexStatement(parsed.value.values, parsed.value.values))
                is GMResult.Err -> parsed
            }
            51 -> when (val id = string(0)) {
                is GMResult.Ok -> ok(NodeIds(listOf(id.value)))
                is GMResult.Err -> id
            }
            52 -> {
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
                when (val added = db.addVertex(existing.values.last(), metadata = metadata)) {
                    is GMResult.Ok -> ok(NodeIds(existing.values + next))
                    is GMResult.Err -> added
                }
            }
            53 -> {
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
            54 -> ok(value(0))
            55 -> {
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
            in 56..72 -> reduceVertex(production, values, db, token)
            73 -> {
                val edge = when (val parsed = link(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val edgeText = when (val parsed = text(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                edge.text = edgeText
                ok(edge)
            }
            74, 75 -> {
                val edge = when (val parsed = link(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val edgeText = when (val parsed = text(1)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                edge.text = edgeText
                ok(edge)
            }
            76 -> ok(value(0))
            77, 78 -> reduceLabeledLink(production, values, db, token)
            79 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Text))
                is GMResult.Err -> raw
            }
            80 -> concatenateText(values, token, production)
            81 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.String))
                is GMResult.Err -> raw
            }
            82 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Markdown))
                is GMResult.Err -> raw
            }
            83, 84 -> reduceLinkStatement(production, values, db, token)
            85 -> ok(value(1))
            86 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Text))
                is GMResult.Err -> raw
            }
            87 -> concatenateText(values, token, production)
            88 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.String))
                is GMResult.Err -> raw
            }
            89 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Markdown))
                is GMResult.Err -> raw
            }
            101, 103 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Text))
                is GMResult.Err -> raw
            }
            102 -> concatenateText(values, token, production)
            104 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(MermaidFlowText(raw.value, FlowLabelType.Markdown))
                is GMResult.Err -> raw
            }
            105 -> {
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
            106 -> {
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
            in 107..120 -> reduceClick(production, values, db, token)
            121 -> {
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
            in 122..127 -> reduceLinkStyle(production, values, db, token)
            128 -> when (val raw = string(0)) {
                is GMResult.Ok -> {
                    val position = raw.value.toIntOrNull()?.let(FlowDb.LinkPosition::Index)
                        ?: return semanticError(token, production, "link index", 0)
                    ok(LinkPositions(mutableListOf(position)))
                }
                is GMResult.Err -> raw
            }
            129 -> {
                val target = when (val parsed = positions(0)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val raw = when (val parsed = string(2)) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                val position = raw.toIntOrNull()?.let(FlowDb.LinkPosition::Index)
                    ?: return semanticError(token, production, "link index", 2)
                target.values += position
                ok(target)
            }
            130 -> when (val raw = string(0)) {
                is GMResult.Ok -> ok(StyleList(listOf(raw.value)))
                is GMResult.Err -> raw
            }
            131 -> {
                val target = values.getOrNull(0) as? StyleList
                    ?: return semanticError(token, production, "style list", 0)
                val raw = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "style", 2)
                ok(StyleList(target.values + raw))
            }
            133 -> concatenateStrings(values, token, production)
            181 -> ok(value(0))
            182, 184 -> concatenateStrings(values, token, production)
            185 -> ok(MermaidFlowDocumentItem.Direction("TB"))
            186 -> ok(MermaidFlowDocumentItem.Direction("BT"))
            187 -> ok(MermaidFlowDocumentItem.Direction("RL"))
            188 -> ok(MermaidFlowDocumentItem.Direction("LR"))
            189 -> ok(MermaidFlowDocumentItem.Direction("TD"))
            else -> ok(values.firstOrNull())
        }
    }

    private fun reduceVertex(
        production: Int,
        values: List<Any?>,
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        val idIndex: Int
        val textIndex: Int?
        val type: String?
        val props: Map<String, String>?
        when (production) {
            56 -> {
                idIndex = 0
                textIndex = 2
                type = "square"
                props = null
            }
            57 -> {
                idIndex = 0
                textIndex = 2
                type = "doublecircle"
                props = null
            }
            58 -> {
                idIndex = 0
                textIndex = 3
                type = "circle"
                props = null
            }
            59 -> {
                idIndex = 0
                textIndex = 2
                type = "ellipse"
                props = null
            }
            60 -> {
                idIndex = 0
                textIndex = 2
                type = "stadium"
                props = null
            }
            61 -> {
                idIndex = 0
                textIndex = 2
                type = "subroutine"
                props = null
            }
            62 -> {
                idIndex = 0
                textIndex = 6
                type = "rect"
                val field = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "property field", 2)
                val propertyValue = values.getOrNull(4) as? String
                    ?: return semanticError(token, production, "property value", 4)
                props = mapOf(field to propertyValue)
            }
            63 -> {
                idIndex = 0
                textIndex = 2
                type = "cylinder"
                props = null
            }
            64 -> {
                idIndex = 0
                textIndex = 2
                type = "round"
                props = null
            }
            65 -> {
                idIndex = 0
                textIndex = 2
                type = "diamond"
                props = null
            }
            66 -> {
                idIndex = 0
                textIndex = 3
                type = "hexagon"
                props = null
            }
            67 -> {
                idIndex = 0
                textIndex = 2
                type = "odd"
                props = null
            }
            68 -> {
                idIndex = 0
                textIndex = 2
                type = "trapezoid"
                props = null
            }
            69 -> {
                idIndex = 0
                textIndex = 2
                type = "inv_trapezoid"
                props = null
            }
            70 -> {
                idIndex = 0
                textIndex = 2
                type = "lean_right"
                props = null
            }
            71 -> {
                idIndex = 0
                textIndex = 2
                type = "lean_left"
                props = null
            }
            72 -> {
                idIndex = 0
                textIndex = null
                type = null
                props = null
            }
            else -> return semanticError(token, production, "vertex production", 0)
        }
        val id = values.getOrNull(idIndex) as? String
            ?: return semanticError(token, production, "vertex id", idIndex)
        val text = textIndex?.let { values.getOrNull(it) as? MermaidFlowText }
        if (textIndex != null && text == null) {
            return semanticError(token, production, "vertex text", textIndex)
        }
        return when (val added = db.addVertex(id, text = text, type = type, props = props)) {
            is GMResult.Ok -> ok(id)
            is GMResult.Err -> added
        }
    }

    private fun reduceLabeledLink(
        production: Int,
        values: List<Any?>,
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        val id: String?
        val start: String
        val edgeText: MermaidFlowText
        val end: String
        if (production == 77) {
            id = null
            start = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "start link", 0)
            edgeText = values.getOrNull(1) as? MermaidFlowText
                ?: return semanticError(token, production, "edge text", 1)
            end = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "end link", 2)
        } else {
            id = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "link id", 0)
            start = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "start link", 1)
            edgeText = values.getOrNull(2) as? MermaidFlowText
                ?: return semanticError(token, production, "edge text", 2)
            end = values.getOrNull(3) as? String
                ?: return semanticError(token, production, "end link", 3)
        }
        return when (val parsed = db.destructLink(end, start)) {
            is GMResult.Ok -> ok(parsed.value.apply {
                text = edgeText
                this.id = id
            })
            is GMResult.Err -> parsed
        }
    }

    private fun reduceLinkStatement(
        production: Int,
        values: List<Any?>,
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        val id = if (production == 84) {
            values.getOrNull(0) as? String
                ?: return semanticError(token, production, "link id", 0)
        } else {
            null
        }
        val linkSourceIndex = if (production == 84) 1 else 0
        val linkSource = values.getOrNull(linkSourceIndex) as? String
            ?: return semanticError(token, production, "link", linkSourceIndex)
        return when (val parsed = db.destructLink(linkSource)) {
            is GMResult.Ok -> ok(parsed.value.apply { this.id = id })
            is GMResult.Err -> parsed
        }
    }

    private fun reduceClick(
        production: Int,
        values: List<Any?>,
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        fun raw(index: Int): String? = values.getOrNull(index) as? String
        when (production) {
            107, 115 -> db.setClickEvent(raw(0).orEmpty(), raw(1))
            108, 116 -> {
                db.setClickEvent(raw(0).orEmpty(), raw(1))
                db.setTooltip(raw(0).orEmpty(), raw(3))
            }
            109 -> db.setClickEvent(raw(0).orEmpty(), raw(1), raw(2))
            110 -> {
                db.setClickEvent(raw(0).orEmpty(), raw(1), raw(2))
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            111 -> db.setLink(raw(0).orEmpty(), raw(2).orEmpty())
            112 -> {
                db.setLink(raw(0).orEmpty(), raw(2).orEmpty())
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            113 -> db.setLink(raw(0).orEmpty(), raw(2).orEmpty(), raw(4))
            114 -> {
                db.setLink(raw(0).orEmpty(), raw(2).orEmpty(), raw(6))
                db.setTooltip(raw(0).orEmpty(), raw(4))
            }
            117 -> db.setLink(raw(0).orEmpty(), raw(1).orEmpty())
            118 -> {
                db.setLink(raw(0).orEmpty(), raw(1).orEmpty())
                db.setTooltip(raw(0).orEmpty(), raw(3))
            }
            119 -> db.setLink(raw(0).orEmpty(), raw(1).orEmpty(), raw(3))
            120 -> {
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
        db: FlowDb,
        token: JisonToken,
    ): GMResult<Any?, MermaidError> {
        val positionValue = values.getOrNull(2)
        val positions = when (positionValue) {
            is LinkPositions -> positionValue.values
            is String -> listOf(FlowDb.LinkPosition.Default)
            else -> return semanticError(token, production, "link positions", 2)
        }
        when (production) {
            122, 123 -> {
                val style = values.getOrNull(4) as? StyleList
                    ?: return semanticError(token, production, "link style", 4)
                when (val updated = db.updateLink(positions, style.values)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
            124, 125 -> {
                val interpolation = values.getOrNull(6) as? String
                    ?: return semanticError(token, production, "interpolation", 6)
                val style = values.getOrNull(8) as? StyleList
                    ?: return semanticError(token, production, "link style", 8)
                when (val updated = db.updateLinkInterpolate(positions, interpolation)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
                when (val updated = db.updateLink(positions, style.values)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
            126, 127 -> {
                val interpolation = values.getOrNull(6) as? String
                    ?: return semanticError(token, production, "interpolation", 6)
                when (val updated = db.updateLinkInterpolate(positions, interpolation)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return updated
                }
            }
        }
        return ok(EmptyStatement)
    }

    private fun addSubgraph(
        db: FlowDb,
        idValue: Any?,
        bodyValue: Any?,
        titleValue: Any?,
        token: JisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val id = when (idValue) {
            null -> null
            is MermaidFlowText -> idValue
            else -> return semanticError(token, production, "subgraph id", 0)
        }
        val title = when (titleValue) {
            null -> null
            is MermaidFlowText -> titleValue
            else -> return semanticError(token, production, "subgraph title", 0)
        }
        val body = bodyValue as? MutableList<*>
            ?: return semanticError(token, production, "subgraph document", 0)
        if (body.any { it !is MermaidFlowDocumentItem }) {
            return semanticError(token, production, "subgraph document item", 0)
        }
        return when (
            val added = db.addSubgraph(
                idValue = id,
                items = body.filterIsInstance<MermaidFlowDocumentItem>(),
                titleValue = title,
            )
        ) {
            is GMResult.Ok -> ok(SubgraphValue(added.value))
            is GMResult.Err -> added
        }
    }

    private fun concatenateStrings(
        values: List<Any?>,
        token: JisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(0) as? String
            ?: return semanticError(token, production, "string", 0)
        val second = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "string", 1)
        return ok(first + second)
    }

    private fun concatenateText(
        values: List<Any?>,
        token: JisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(0) as? MermaidFlowText
            ?: return semanticError(token, production, "FlowText", 0)
        val second = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "text token", 1)
        return ok(first.copy(text = first.text + second))
    }

    private fun expectedMessage(state: Int, token: JisonToken): String {
        val expected = FlowJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(FlowJisonTables.terminalNames::get)
            .filterNot { it == "error" }
            .joinToString()
        return "Unexpected '${token.name}'${if (expected.isEmpty()) "" else "; expected $expected"}"
    }

    private fun <T> semanticError(
        token: JisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> =
        parseError(token, "Production $production expected $expected at value $index")

    private fun <T> parseError(
        token: JisonToken,
        message: String,
    ): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(token.line, token.column + 1, message))

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
        val values: MutableList<FlowDb.LinkPosition>,
    )

    private data class SubgraphValue(
        val id: String,
    )

    private object EmptyStatement

    private companion object {
        val TRAILING_METADATA_WHITESPACE = Regex("""\}\s*\n""")
    }
}
