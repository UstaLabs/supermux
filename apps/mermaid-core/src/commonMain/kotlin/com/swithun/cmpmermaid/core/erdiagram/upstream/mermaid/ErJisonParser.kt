package com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.map

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12
 * erDiagram.jison semantic actions.
 */
internal class ErJisonParser(
    private val diagramTitle: String? = null,
) {
    fun parse(source: String): GMResult<ErDb, MermaidError> {
        val db = ErDb(diagramTitle)
        val lexer = ErJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: ErJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = ErJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is ErJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is ErJisonCell.Reduce -> {
                    val production = ErJisonTables.productions
                        .getOrNull(action.production)
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
                    val goto = ErJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? ErJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                ErJisonCell.Accept -> return GMResult.Ok(db)
                is ErJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: ErDb,
        token: ErJisonToken,
    ): GMResult<Any?, MermaidError> {
        return when (production) {
        2, 5, 6, 28, 29, 30 -> GMResult.Ok(mutableListOf<ErDocumentEntry>())
        3 -> document(values, token, production)
        4 -> GMResult.Ok(values.firstOrNull())
        in 7..10 -> relationshipStatement(production, values, db, token)
        in 11..22 -> entityStatement(production, values, db, token)
        23, 24 -> stringValue(
            values,
            1,
            token,
            production,
            "accessibility title",
        ).map { value ->
            db.setAccessibilityTitle(value.trim())
            mutableListOf<ErDocumentEntry>()
        }
        25 -> stringValue(
            values,
            1,
            token,
            production,
            "accessibility description",
        ).map { value ->
            db.setAccessibilityDescription(value.trim())
            mutableListOf<ErDocumentEntry>()
        }
        26 -> stringValue(
            values,
            0,
            token,
            production,
            "accessibility description",
        ).map { value ->
            db.setAccessibilityDescription(value.trim())
            mutableListOf<ErDocumentEntry>()
        }
        27 -> directionValue(values.firstOrNull(), token, production).map { direction ->
            if (db.subgraphDepth == 0) {
                db.setDirection(direction.value)
                mutableListOf()
            } else {
                mutableListOf(direction)
            }
        }
        31 -> addSubGraph(values, db, token, production)
        32 -> stringValue(values, 1, token, production, "subgraph id").map { id ->
            db.subgraphDepth += 1
            ErSubGraphHeader(id = id, text = id)
        }
        33 -> {
            val id = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "subgraph id", 1)
            val title = values.getOrNull(3) as? String
                ?: return semanticError(token, production, "subgraph title", 3)
            db.subgraphDepth += 1
            GMResult.Ok(ErSubGraphHeader(id = id, text = title))
        }
        34, 59, 60, 61, 62, 86 -> GMResult.Ok(values.firstOrNull())
        35 -> concatenate(values, 0, 1, " ", token, production)
        36 -> GMResult.Ok(ErDirectionStatement("TB"))
        37 -> GMResult.Ok(ErDirectionStatement("BT"))
        38 -> GMResult.Ok(ErDirectionStatement("RL"))
        39 -> GMResult.Ok(ErDirectionStatement("LR"))
        40 -> {
            val ids = stringList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "class definition ids", 1)
            val styles = stringList(values.getOrNull(2), token, production, 2)
                ?: return semanticError(token, production, "class definition styles", 2)
            db.addClass(ids, styles)
            GMResult.Ok(mutableListOf<ErDocumentEntry>())
        }
        41, 42 -> stringValue(values, 0, token, production, "id").map {
            mutableListOf(it)
        }
        43, 44 -> appendString(values, 0, 2, token, production)
        45 -> {
            val ids = stringList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "entity ids", 1)
            val classes = stringList(values.getOrNull(2), token, production, 2)
                ?: return semanticError(token, production, "class ids", 2)
            db.setClass(ids, classes)
            GMResult.Ok(mutableListOf<ErDocumentEntry>())
        }
        46 -> {
            val ids = stringList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "entity ids", 1)
            val styles = stringList(values.getOrNull(2), token, production, 2)
                ?: return semanticError(token, production, "entity styles", 2)
            db.addCssStyles(ids, styles)
            GMResult.Ok(mutableListOf<ErDocumentEntry>())
        }
        47 -> stringValue(values, 0, token, production, "style").map {
            mutableListOf(it)
        }
        48 -> appendString(values, 0, 2, token, production)
        49, in 51..57, 69, 71 -> GMResult.Ok(values.firstOrNull())
        50, 70 -> concatenate(values, 0, 1, "", token, production)
        58, 75, 84, 85 -> stringValue(
            values,
            0,
            token,
            production,
            "quoted text",
        ).map { it.replace("\"", "") }
        63 -> attributeValue(values.firstOrNull(), token, production, 0).map {
            mutableListOf(it)
        }
        64 -> {
            val attribute = values.getOrNull(0) as? ErAttribute
                ?: return semanticError(token, production, "attribute", 0)
            val attributes = attributeList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "attributes", 1)
            attributes += attribute
            GMResult.Ok(attributes)
        }
        65 -> attribute(values, null, null, token, production)
        66 -> attribute(values, keysIndex = 2, commentIndex = null, token, production)
        67 -> attribute(values, keysIndex = null, commentIndex = 2, token, production)
        68 -> attribute(values, keysIndex = 2, commentIndex = 3, token, production)
        72 -> {
            val key = values.firstOrNull() as? ErAttributeKey
                ?: return semanticError(token, production, "attribute key", 0)
            GMResult.Ok(mutableListOf(key))
        }
        73 -> {
            val keys = attributeKeyList(values.getOrNull(0), token, production, 0)
                ?: return semanticError(token, production, "attribute keys", 0)
            val key = values.getOrNull(2) as? ErAttributeKey
                ?: return semanticError(token, production, "attribute key", 2)
            keys += key
            GMResult.Ok(keys)
        }
        74 -> attributeKey(values.firstOrNull(), token, production)
        76 -> {
            val cardB = values.getOrNull(0) as? ErCardinality
                ?: return semanticError(token, production, "start cardinality", 0)
            val relation = values.getOrNull(1) as? ErIdentification
                ?: return semanticError(token, production, "identification", 1)
            val cardA = values.getOrNull(2) as? ErCardinality
                ?: return semanticError(token, production, "end cardinality", 2)
            GMResult.Ok(ErRelationshipSpec(cardA, relation, cardB))
        }
        77 -> GMResult.Ok(ErCardinality.ZeroOrOne)
        78 -> GMResult.Ok(ErCardinality.ZeroOrMore)
        79 -> GMResult.Ok(ErCardinality.OneOrMore)
        80 -> GMResult.Ok(ErCardinality.OnlyOne)
        81 -> GMResult.Ok(ErCardinality.MdParent)
        82 -> GMResult.Ok(ErIdentification.NonIdentifying)
        83 -> GMResult.Ok(ErIdentification.Identifying)
            else -> GMResult.Ok(values.firstOrNull())
        }
    }

    private fun document(
        values: List<Any?>,
        token: ErJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val document = documentList(values.getOrNull(0), token, production, 0)
            ?: return semanticError(token, production, "document", 0)
        val line = documentList(values.getOrNull(1), token, production, 1)
            ?: return semanticError(token, production, "line", 1)
        document += line
        return GMResult.Ok(document)
    }

    private fun relationshipStatement(
        production: Int,
        values: List<Any?>,
        db: ErDb,
        token: ErJisonToken,
    ): GMResult<Any?, MermaidError> {
        val indexes = when (production) {
            7 -> RelationshipIndexes(0, null, 1, 2, null, 4)
            8 -> RelationshipIndexes(0, 2, 3, 4, 6, 8)
            9 -> RelationshipIndexes(0, 2, 3, 4, null, 6)
            else -> RelationshipIndexes(0, null, 1, 2, 4, 6)
        }
        val first = values.getOrNull(indexes.first) as? String
            ?: return semanticError(token, production, "first entity", indexes.first)
        val second = values.getOrNull(indexes.second) as? String
            ?: return semanticError(token, production, "second entity", indexes.second)
        val specification = values.getOrNull(indexes.specification) as? ErRelationshipSpec
            ?: return semanticError(
                token,
                production,
                "relationship specification",
                indexes.specification,
            )
        val role = values.getOrNull(indexes.role) as? String
            ?: return semanticError(token, production, "relationship role", indexes.role)
        db.addEntity(first)
        db.addEntity(second)
        db.addRelationship(first, role, second, specification)
        indexes.firstClasses?.let { index ->
            val classes = stringList(values.getOrNull(index), token, production, index)
                ?: return semanticError(token, production, "first entity classes", index)
            db.setClass(listOf(first), classes)
        }
        indexes.secondClasses?.let { index ->
            val classes = stringList(values.getOrNull(index), token, production, index)
                ?: return semanticError(token, production, "second entity classes", index)
            db.setClass(listOf(second), classes)
        }
        return GMResult.Ok(
            mutableListOf<ErDocumentEntry>(
                ErEntityReference(first),
                ErEntityReference(second),
            ),
        )
    }

    private fun entityStatement(
        production: Int,
        values: List<Any?>,
        db: ErDb,
        token: ErJisonToken,
    ): GMResult<Any?, MermaidError> {
        val name = values.firstOrNull() as? String
            ?: return semanticError(token, production, "entity name", 0)
        val aliasIndex = when (production) {
            in 17..22 -> 2
            else -> null
        }
        val alias = aliasIndex?.let { index ->
            values.getOrNull(index) as? String
                ?: return semanticError(token, production, "entity alias", index)
        }.orEmpty()
        db.addEntity(name, alias)

        val attributesIndex = when (production) {
            11 -> 2
            12 -> 4
            17 -> 5
            18 -> 7
            else -> null
        }
        attributesIndex?.let { index ->
            val attributes = attributeList(values.getOrNull(index), token, production, index)
                ?: return semanticError(token, production, "attributes", index)
            db.addAttributes(name, attributes)
        }

        val classesIndex = when (production) {
            12, 14, 16 -> 2
            18, 20, 22 -> if (production == 18) 5 else if (production == 20) 5 else 5
            else -> null
        }
        classesIndex?.let { index ->
            val classes = stringList(values.getOrNull(index), token, production, index)
                ?: return semanticError(token, production, "entity classes", index)
            db.setClass(listOf(name), classes)
        }
        return GMResult.Ok(
            mutableListOf<ErDocumentEntry>(ErEntityReference(name)),
        )
    }

    private fun addSubGraph(
        values: List<Any?>,
        db: ErDb,
        token: ErJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val header = values.getOrNull(0) as? ErSubGraphHeader
            ?: return semanticError(token, production, "subgraph header", 0)
        val document = documentList(values.getOrNull(1), token, production, 1)
            ?: return semanticError(token, production, "subgraph document", 1)
        db.subgraphDepth = (db.subgraphDepth.coerceAtLeast(1) - 1)
        return GMResult.Ok(
            mutableListOf<ErDocumentEntry>(
                ErSubGraphReference(db.addSubGraph(header, document)),
            ),
        )
    }

    private fun attribute(
        values: List<Any?>,
        keysIndex: Int?,
        commentIndex: Int?,
        token: ErJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val type = values.getOrNull(0) as? String
            ?: return semanticError(token, production, "attribute type", 0)
        val name = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "attribute name", 1)
        val keys = keysIndex?.let { index ->
            attributeKeyList(values.getOrNull(index), token, production, index)
                ?: return semanticError(token, production, "attribute keys", index)
        }.orEmpty()
        val comment = commentIndex?.let { index ->
            values.getOrNull(index) as? String
                ?: return semanticError(token, production, "attribute comment", index)
        }.orEmpty()
        return GMResult.Ok(ErAttribute(type, name, keys, comment))
    }

    private fun appendString(
        values: List<Any?>,
        listIndex: Int,
        valueIndex: Int,
        token: ErJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val list = stringList(values.getOrNull(listIndex), token, production, listIndex)
            ?: return semanticError(token, production, "string list", listIndex)
        val value = values.getOrNull(valueIndex) as? String
            ?: return semanticError(token, production, "string", valueIndex)
        list += value
        return GMResult.Ok(list)
    }

    private fun concatenate(
        values: List<Any?>,
        firstIndex: Int,
        secondIndex: Int,
        separator: String,
        token: ErJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(firstIndex) as? String
            ?: return semanticError(token, production, "string", firstIndex)
        val second = values.getOrNull(secondIndex) as? String
            ?: return semanticError(token, production, "string", secondIndex)
        return GMResult.Ok(first + separator + second)
    }

    private fun stringValue(
        values: List<Any?>,
        index: Int,
        token: ErJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return semanticError(token, production, expected, index)
        return GMResult.Ok(value)
    }

    private fun directionValue(
        value: Any?,
        token: ErJisonToken,
        production: Int,
    ): GMResult<ErDirectionStatement, MermaidError> {
        val direction = value as? ErDirectionStatement
            ?: return semanticError(token, production, "direction", 0)
        return GMResult.Ok(direction)
    }

    @Suppress("UNCHECKED_CAST")
    private fun documentList(
        value: Any?,
        token: ErJisonToken,
        production: Int,
        index: Int,
    ): MutableList<ErDocumentEntry>? =
        (value as? MutableList<*>)
            ?.takeIf { list -> list.all { it is ErDocumentEntry } }
            ?.let { it as MutableList<ErDocumentEntry> }

    @Suppress("UNCHECKED_CAST")
    private fun stringList(
        value: Any?,
        token: ErJisonToken,
        production: Int,
        index: Int,
    ): MutableList<String>? =
        (value as? MutableList<*>)
            ?.takeIf { list -> list.all { it is String } }
            ?.let { it as MutableList<String> }

    @Suppress("UNCHECKED_CAST")
    private fun attributeList(
        value: Any?,
        token: ErJisonToken,
        production: Int,
        index: Int,
    ): MutableList<ErAttribute>? =
        (value as? MutableList<*>)
            ?.takeIf { list -> list.all { it is ErAttribute } }
            ?.let { it as MutableList<ErAttribute> }

    private fun attributeValue(
        value: Any?,
        token: ErJisonToken,
        production: Int,
        index: Int,
    ): GMResult<ErAttribute, MermaidError> {
        val attribute = value as? ErAttribute
            ?: return semanticError(token, production, "attribute", index)
        return GMResult.Ok(attribute)
    }

    @Suppress("UNCHECKED_CAST")
    private fun attributeKeyList(
        value: Any?,
        token: ErJisonToken,
        production: Int,
        index: Int,
    ): MutableList<ErAttributeKey>? =
        (value as? MutableList<*>)
            ?.takeIf { list -> list.all { it is ErAttributeKey } }
            ?.let { it as MutableList<ErAttributeKey> }

    private fun attributeKey(
        value: Any?,
        token: ErJisonToken,
        production: Int,
    ): GMResult<ErAttributeKey, MermaidError> {
        val key = when ((value as? String)?.uppercase()) {
            "PK" -> ErAttributeKey.Primary
            "FK" -> ErAttributeKey.Foreign
            "UK" -> ErAttributeKey.Unique
            else -> return semanticError(token, production, "attribute key", 0)
        }
        return GMResult.Ok(key)
    }

    private fun expectedMessage(
        state: Int,
        token: ErJisonToken,
    ): String {
        val expected = ErJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(ErJisonTables.terminalNames::get)
            .filterNot { it == "error" }
            .distinct()
            .sorted()
        return buildString {
            append("Unexpected ")
            append(if (token.symbol == PHYSICAL_EOF) "end of input" else "'${token.text}'")
            if (expected.isNotEmpty()) {
                append("; expected ")
                append(expected.joinToString())
            }
        }
    }

    private fun <T> semanticError(
        token: ErJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = parseError(
        token,
        "ER parser production $production expected $expected at value $index",
    )

    private fun <T> parseError(
        token: ErJisonToken,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line,
            column = token.column + 1,
            message = message,
        ),
    )

    private data class RelationshipIndexes(
        val first: Int,
        val firstClasses: Int?,
        val specification: Int,
        val second: Int,
        val secondClasses: Int?,
        val role: Int,
    )

    private companion object {
        const val PHYSICAL_EOF = 1
    }
}
