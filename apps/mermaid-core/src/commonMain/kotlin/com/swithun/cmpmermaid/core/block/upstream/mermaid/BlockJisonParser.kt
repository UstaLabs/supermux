package com.swithun.cmpmermaid.core.block.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12.0.0
 * block.jison semantic actions.
 */
internal class BlockJisonParser(
    private val config: MermaidRenderOptions = MermaidRenderOptions(),
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<BlockDb, MermaidError> {
        val db = BlockDb(config, diagramTitle)
        val lexer = BlockJisonLexer(prepareTextForParsing(source))
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: BlockJisonToken? = null

        while (true) {
            val state = states.last()
            val defaultAction = BlockJisonTables.defaultActions[state]
            val token = if (defaultAction == null) {
                when (val current = lookahead) {
                    null -> when (val next = lexer.next()) {
                        is GMResult.Ok -> next.value.also { lookahead = it }
                        is GMResult.Err -> return next.withLineOffset()
                    }
                    else -> current
                }
            } else {
                lookahead ?: BlockJisonToken(
                    symbol = PHYSICAL_EOF,
                    name = "\$end",
                    text = "",
                    line = 1,
                    column = 0,
                )
            }
            val action = defaultAction
                ?: BlockJisonTables.states
                    .getOrNull(state)
                    ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is BlockJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is BlockJisonCell.Reduce -> {
                    val production = BlockJisonTables.productions.getOrNull(action.production)
                        ?: return parseError(
                            token,
                            "Unknown production ${action.production}",
                        )
                    if (production.length >= states.size || production.length >= values.size) {
                        return parseError(
                            token,
                            "Invalid parser stack for production ${action.production}",
                        )
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
                    val goto = BlockJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? BlockJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                BlockJisonCell.Accept -> return GMResult.Ok(db)
                is BlockJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: BlockDb,
        token: BlockJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        1, 2, 3, 4, 5, 6, 8, 9, 10, 11 -> ok(values.firstOrNull())
        7 -> blocks(values, 1, token, production).flatMapValue { hierarchy ->
            db.setHierarchy(hierarchy).mapValue { hierarchy }
        }
        12 -> statementBlocks(values.firstOrNull(), token, production)
        13 -> statementBlocks(values.firstOrNull(), token, production).flatMapValue { head ->
            blocks(values, 1, token, production).mapValue { tail -> head + tail }
        }
        14 -> string(values, 0, token, production, "edge").mapValue { edge ->
            BlockLinkValue(edgeTypeString = edge, label = "")
        }
        15 -> string(values, 2, token, production, "edge label").flatMapValue { label ->
            string(values, 3, token, production, "edge").mapValue { edge ->
                BlockLinkValue(edgeTypeString = edge, label = label)
            }
        }
        18 -> string(values, 0, token, production, "space width").mapValue { width ->
            Block(
                id = db.generateId("space"),
                type = BlockType.Space,
                label = "",
                width = width.toIntOrNull() ?: 1,
            )
        }
        16, 17, 19, 20, 21, 22 -> ok(values.firstOrNull())
        23 -> block(values, 0, token, production).flatMapValue { start ->
            link(values, 1, token, production).flatMapValue { edge ->
                node(values, 2, token, production).mapValue { end ->
                    listOf(
                        start.copy(
                            children = start.children.toMutableList(),
                            classes = start.classes.toMutableList(),
                            styles = start.styles.toMutableList(),
                        ),
                        Block(
                            id = "${start.id}-${end.id}",
                            type = BlockType.Edge,
                            start = start.id,
                            end = end.id,
                            label = edge.label,
                            thickness = BlockDb.edgeStringToThickness(edge.edgeTypeString),
                            pattern = BlockDb.edgeStringToPattern(edge.edgeTypeString),
                            directions = end.directions,
                            arrowTypeEnd = BlockDb.edgeStringToEnd(edge.edgeTypeString),
                            arrowTypeStart = BlockDb.edgeStringToStart(edge.edgeTypeString),
                        ),
                        end.toBlock(),
                    )
                }
            }
        }
        24 -> node(values, 0, token, production).flatMapValue { node ->
            string(values, 1, token, production, "block width").flatMapValue { width ->
                val parsed = width.toIntOrNull()
                if (parsed == null) {
                    parseError(
                        token,
                        "Block width must be an integer",
                    )
                } else {
                    ok(node.toBlock(widthInColumns = parsed))
                }
            }
        }
        25 -> node(values, 0, token, production).mapValue { node -> node.toBlock() }
        26 -> string(values, 0, token, production, "columns").flatMapValue { columns ->
            val parsed = columns.toIntOrNull()
            if (parsed == null) {
                parseError(
                    token,
                    "Block columns must be an integer",
                )
            } else {
                ok(Block(id = "columns", type = BlockType.ColumnSetting, columns = parsed))
            }
        }
        27 -> block(values, 1, token, production).flatMapValue { heading ->
            blocks(values, 2, token, production).mapValue { children ->
                heading.copy(
                    type = BlockType.Composite,
                    children = children.toMutableList(),
                )
            }
        }
        28 -> blocks(values, 1, token, production).mapValue { children ->
            Block(
                id = db.generateId("composite"),
                type = BlockType.Composite,
                label = "",
                children = children.toMutableList(),
            )
        }
        29 -> string(values, 0, token, production, "block id").mapValue(::BlockNodeValue)
        30 -> string(values, 0, token, production, "block id").flatMapValue { id ->
            shape(values, 1, token, production).mapValue { shape ->
                BlockNodeValue(
                    id = id,
                    label = shape.label,
                    typeString = shape.typeString,
                    directions = shape.directions,
                )
            }
        }
        31 -> string(values, 0, token, production, "direction").mapValue(::listOf)
        32 -> string(values, 0, token, production, "direction").flatMapValue { head ->
            strings(values, 1, token, production, "direction list").mapValue { tail ->
                listOf(head) + tail
            }
        }
        33 -> string(values, 0, token, production, "shape start").flatMapValue { start ->
            string(values, 1, token, production, "block label").flatMapValue { label ->
                string(values, 2, token, production, "shape end").mapValue { end ->
                    BlockShapeValue(typeString = start + end, label = label)
                }
            }
        }
        34 -> string(values, 0, token, production, "block arrow start").flatMapValue { start ->
            string(values, 1, token, production, "block arrow label").flatMapValue { label ->
                strings(values, 2, token, production, "block arrow directions")
                    .flatMapValue { directions ->
                        string(values, 3, token, production, "block arrow end").mapValue { end ->
                            BlockShapeValue(
                                typeString = start + end,
                                label = label,
                                directions = directions,
                            )
                        }
                    }
            }
        }
        35, 36 -> string(values, 1, token, production, "class id").flatMapValue { id ->
            string(values, 2, token, production, "class styles").mapValue { styles ->
                Block(
                    id = id.trim(),
                    type = BlockType.ClassDefinition,
                    css = styles.trim(),
                )
            }
        }
        37 -> string(values, 1, token, production, "class target").flatMapValue { id ->
            string(values, 2, token, production, "class name").mapValue { styleClass ->
                Block(
                    id = id.trim(),
                    type = BlockType.ApplyClass,
                    styleClass = styleClass.trim(),
                )
            }
        }
        38 -> string(values, 1, token, production, "style target").flatMapValue { id ->
            string(values, 2, token, production, "styles").mapValue { styles ->
                Block(
                    id = id.trim(),
                    type = BlockType.ApplyStyles,
                    stylesStr = styles.trim(),
                )
            }
        }
        else -> ok(values.firstOrNull())
    }

    private fun BlockNodeValue.toBlock(widthInColumns: Int = 1): Block =
        Block(
            id = id,
            label = label,
            type = BlockDb.typeStringToType(typeString),
            directions = directions,
            widthInColumns = widthInColumns,
        )

    private fun block(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<Block, MermaidError> =
        (values.getOrNull(index) as? Block)
            ?.let(::ok)
            ?: semanticError(token, production, "block", index)

    private fun node(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<BlockNodeValue, MermaidError> =
        (values.getOrNull(index) as? BlockNodeValue)
            ?.let(::ok)
            ?: semanticError(token, production, "block node", index)

    private fun link(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<BlockLinkValue, MermaidError> =
        (values.getOrNull(index) as? BlockLinkValue)
            ?.let(::ok)
            ?: semanticError(token, production, "block link", index)

    private fun shape(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<BlockShapeValue, MermaidError> =
        (values.getOrNull(index) as? BlockShapeValue)
            ?.let(::ok)
            ?: semanticError(token, production, "block shape", index)

    private fun blocks(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<List<Block>, MermaidError> {
        val value = values.getOrNull(index)
        if (value !is List<*> || value.any { item -> item !is Block }) {
            return semanticError(token, production, "block document", index)
        }
        return ok(value.filterIsInstance<Block>())
    }

    private fun strings(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
        expected: String,
    ): GMResult<List<String>, MermaidError> {
        val value = values.getOrNull(index)
        if (value !is List<*> || value.any { item -> item !is String }) {
            return semanticError(token, production, expected, index)
        }
        return ok(value.filterIsInstance<String>())
    }

    private fun statementBlocks(
        value: Any?,
        token: BlockJisonToken,
        production: Int,
    ): GMResult<List<Block>, MermaidError> = when (value) {
        is Block -> ok(listOf(value))
        is List<*> -> if (value.all { item -> item is Block }) {
            ok(value.filterIsInstance<Block>())
        } else {
            semanticError(token, production, "block statement", 0)
        }
        else -> semanticError(token, production, "block statement", 0)
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: BlockJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> =
        (values.getOrNull(index) as? String)
            ?.let(::ok)
            ?: semanticError(token, production, expected, index)

    private fun expectedMessage(
        state: Int,
        token: BlockJisonToken,
    ): String {
        val expected = BlockJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(BlockJisonTables.terminalNames::get)
            .filterNot { name -> name == "error" }
            .distinct()
            .sorted()
        return buildString {
            append("Unexpected ")
            append(token.name)
            if (token.text.isNotEmpty()) append(" '${token.text}'")
            if (expected.isNotEmpty()) append("; expected ${expected.joinToString()}")
        }
    }

    private fun <T> semanticError(
        token: BlockJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = parseError(
        token,
        "Block parser production $production expected $expected at value $index",
    )

    private fun <T> parseError(
        token: BlockJisonToken,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line + lineOffset,
            column = token.column + 1,
            message = message,
        ),
    )

    private fun <T> GMResult<T, MermaidError>.withLineOffset(): GMResult<T, MermaidError> =
        when (this) {
            is GMResult.Ok -> this
            is GMResult.Err -> {
                val current = error
                if (current is MermaidError.Parse) {
                    GMResult.Err(current.copy(line = current.line + lineOffset))
                } else {
                    this
                }
            }
        }

    private fun <T> ok(value: T): GMResult<T, MermaidError> = GMResult.Ok(value)

    private inline fun <T, R> GMResult<T, MermaidError>.mapValue(
        transform: (T) -> R,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> GMResult.Ok(transform(value))
        is GMResult.Err -> this
    }

    private inline fun <T, R> GMResult<T, MermaidError>.flatMapValue(
        transform: (T) -> GMResult<R, MermaidError>,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> transform(value)
        is GMResult.Err -> this
    }

    private companion object {
        const val PHYSICAL_EOF = 1

        // Mermaid.js 12.0.0:
        // packages/mermaid/src/diagrams/block/blockUtils.ts -> prepareTextForParsing.
        fun prepareTextForParsing(source: String): String {
            val normalized = StringBuilder(source.length)
            var index = 0
            while (index < source.length) {
                val character = source[index]
                if (character == '\r' || character == '\n') {
                    normalized.append('\n')
                    do {
                        index += 1
                    } while (
                        index < source.length &&
                        (source[index] == '\r' || source[index] == '\n')
                    )
                } else {
                    normalized.append(character)
                    index += 1
                }
            }
            return normalized.toString().trim()
        }
    }
}
