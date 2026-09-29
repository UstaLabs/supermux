package com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/eventmodeling/event-modeling.langium,
 * tokenBuilder.ts, and event-modeling-validator.ts.
 */
internal class EventModelingParser(
    private val options: MermaidRenderOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<EventModelingDb, MermaidError> {
        val cursor = Cursor(
            source = source.replace("\r\n", "\n").replace('\r', '\n'),
            lineOffset = lineOffset,
        )
        cursor.skipTrivia()
        cursor.triviaError()?.let { return it }
        when (val header = cursor.consumeKeyword(HEADER)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return header
        }

        val frames = mutableListOf<EventModelingFrame>()
        val modelEntities = mutableListOf<String>()
        val dataEntities = mutableListOf<EventModelingDataEntity>()
        val notes = mutableListOf<EventModelingNoteEntity>()
        val gwts = mutableListOf<EventModelingGwt>()
        var title: String? = null
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null

        while (true) {
            cursor.skipTrivia()
            cursor.triviaError()?.let { return it }
            if (cursor.isAtEnd()) {
                break
            }
            when {
                cursor.hasKeyword("accDescr") -> {
                    when (val parsed = cursor.parseAccessibilityDescription()) {
                        is GMResult.Ok -> accessibilityDescription = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("accTitle") -> {
                    when (val parsed = cursor.parseColonMetadata("accTitle")) {
                        is GMResult.Ok -> accessibilityTitle = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("title") -> {
                    title = cursor.parseTitle()
                }
                cursor.hasKeyword("tf") || cursor.hasKeyword("timeframe") -> {
                    when (val parsed = cursor.parseFrame(EventModelingFrameKind.TimeFrame)) {
                        is GMResult.Ok -> frames += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("rf") || cursor.hasKeyword("resetframe") -> {
                    when (val parsed = cursor.parseFrame(EventModelingFrameKind.ResetFrame)) {
                        is GMResult.Ok -> frames += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("entity") -> {
                    cursor.consumeKnownKeyword("entity")
                    cursor.skipWhitespaceAndComments()
                    when (val parsed = cursor.parseQualifiedName()) {
                        is GMResult.Ok -> modelEntities += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("data") -> {
                    when (val parsed = cursor.parseDataEntity()) {
                        is GMResult.Ok -> dataEntities += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("note") -> {
                    when (val parsed = cursor.parseNoteEntity()) {
                        is GMResult.Ok -> notes += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword("gwt") -> {
                    when (val parsed = cursor.parseGwt()) {
                        is GMResult.Ok -> gwts += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                else -> return cursor.error(
                    "Expected an Event Modeling frame, entity, data, note, gwt, or metadata",
                )
            }
        }

        val ast = EventModelingAst(
            frames = frames,
            modelEntities = modelEntities,
            dataEntities = dataEntities,
            noteEntities = notes,
            gwtEntities = gwts,
            title = title,
            accessibilityTitle = accessibilityTitle,
            accessibilityDescription = accessibilityDescription,
        )
        when (val validated = validateReferences(ast, cursor)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validated
        }
        return GMResult.Ok(
            EventModelingDb(
                config = options.eventModeling,
                ast = ast,
                frontmatterTitle = diagramTitle,
                maxEdges = options.maxEdges,
            ),
        )
    }

    private fun validateReferences(
        ast: EventModelingAst,
        cursor: Cursor,
    ): GMResult<Unit, MermaidError> {
        val framesByName = ast.frames.associateBy(EventModelingFrame::name)
        val dataNames = ast.dataEntities.mapTo(mutableSetOf(), EventModelingDataEntity::name)
        val entityNames = ast.modelEntities.toSet()

        ast.frames.forEach { frame ->
            frame.sourceFrameNames.forEach { sourceName ->
                val source = framesByName[sourceName]
                    ?: return cursor.errorAtEnd(
                        "Could not resolve source frame '$sourceName'",
                    )
                val expected = expectedSource(frame.modelEntityType)
                if (expected != null && source.modelEntityType !in expected.allowedTypes) {
                    return cursor.errorAtEnd(
                        "A ${expected.targetLabel} can only receive input from a " +
                            "${expected.sourceLabel}, not from '${source.modelEntityType}'.",
                    )
                }
            }
            frame.dataReference?.let { reference ->
                if (reference !in dataNames) {
                    return cursor.errorAtEnd(
                        "Could not resolve data entity '$reference'",
                    )
                }
            }
        }
        ast.noteEntities.forEach { note ->
            if (note.sourceFrameName !in framesByName) {
                return cursor.errorAtEnd(
                    "Could not resolve note source frame '${note.sourceFrameName}'",
                )
            }
        }
        ast.gwtEntities.forEach { gwt ->
            if (gwt.sourceFrameName !in framesByName) {
                return cursor.errorAtEnd(
                    "Could not resolve gwt source frame '${gwt.sourceFrameName}'",
                )
            }
            (
                gwt.givenStatements +
                    gwt.whenStatements +
                    gwt.thenStatements
                ).forEach { statement ->
                if (statement.entityIdentifier !in entityNames) {
                    return cursor.errorAtEnd(
                        "Could not resolve model entity '${statement.entityIdentifier}'",
                    )
                }
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun expectedSource(targetType: String): SourceExpectation? = when (targetType) {
        "cmd", "command" -> SourceExpectation(
            allowedTypes = setOf("ui", "pcr", "processor"),
            targetLabel = "command",
            sourceLabel = "ui or processor",
        )
        "evt", "event" -> SourceExpectation(
            allowedTypes = setOf("cmd", "command"),
            targetLabel = "event",
            sourceLabel = "command",
        )
        "rmo", "readmodel" -> SourceExpectation(
            allowedTypes = setOf("evt", "event"),
            targetLabel = "read model",
            sourceLabel = "event",
        )
        "pcr", "processor" -> SourceExpectation(
            allowedTypes = setOf("rmo", "readmodel"),
            targetLabel = "processor",
            sourceLabel = "read model",
        )
        "ui" -> SourceExpectation(
            allowedTypes = setOf("rmo", "readmodel"),
            targetLabel = "ui",
            sourceLabel = "read model",
        )
        else -> null
    }

    private data class SourceExpectation(
        val allowedTypes: Set<String>,
        val targetLabel: String,
        val sourceLabel: String,
    )

    private class Cursor(
        private val source: String,
        private val lineOffset: Int,
    ) {
        private var index: Int = 0
        private var unterminatedBlockCommentIndex: Int? = null

        fun isAtEnd(): Boolean = index >= source.length

        fun triviaError(): GMResult.Err<MermaidError>? =
            unterminatedBlockCommentIndex?.let { opening ->
                errorAt(opening, "Unterminated Event Modeling block comment")
            }

        fun hasKeyword(keyword: String): Boolean {
            if (!source.startsWith(keyword, index)) {
                return false
            }
            return !source.getOrNull(index + keyword.length).isIdentifierPart()
        }

        fun consumeKeyword(keyword: String): GMResult<Unit, MermaidError> {
            if (!hasKeyword(keyword)) {
                return error("Expected '$keyword' diagram header")
            }
            index += keyword.length
            return GMResult.Ok(Unit)
        }

        fun consumeKnownKeyword(keyword: String) {
            index += keyword.length
        }

        fun parseFrame(
            kind: EventModelingFrameKind,
        ): GMResult<EventModelingFrame, MermaidError> {
            val keyword = when {
                kind == EventModelingFrameKind.TimeFrame && hasKeyword("timeframe") ->
                    "timeframe"
                kind == EventModelingFrameKind.TimeFrame -> "tf"
                kind == EventModelingFrameKind.ResetFrame && hasKeyword("resetframe") ->
                    "resetframe"
                else -> "rf"
            }
            consumeKnownKeyword(keyword)
            skipWhitespaceAndComments()
            val name = when (val parsed = parseFrameIdentifier()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val modelEntityType = when (val parsed = parseModelEntityType()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val entityIdentifier = when (val parsed = parseQualifiedName()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val sourceFrames = mutableListOf<String>()
            while (true) {
                skipWhitespaceAndComments()
                if (!consume("->>")) {
                    break
                }
                skipWhitespaceAndComments()
                when (val parsed = parseFrameIdentifier()) {
                    is GMResult.Ok -> sourceFrames += parsed.value
                    is GMResult.Err -> return parsed
                }
            }
            skipWhitespaceAndComments()
            val dataReference = if (consume("[[")) {
                skipWhitespaceAndComments()
                val reference = when (val parsed = parseIdentifier("data entity identifier")) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                skipWhitespaceAndComments()
                if (!consume("]]")) {
                    return error("Expected ']]' after data entity reference")
                }
                skipWhitespaceAndComments()
                reference
            } else {
                null
            }
            val typedInline = when (val parsed = parseOptionalTypedInlineData()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            return GMResult.Ok(
                EventModelingFrame(
                    kind = kind,
                    name = name,
                    modelEntityType = modelEntityType,
                    entityIdentifier = entityIdentifier,
                    sourceFrameNames = sourceFrames,
                    dataReference = dataReference,
                    dataType = typedInline?.first,
                    dataInlineValue = typedInline?.second,
                ),
            )
        }

        fun parseDataEntity(): GMResult<EventModelingDataEntity, MermaidError> {
            consumeKnownKeyword("data")
            skipWhitespaceAndComments()
            val name = when (val parsed = parseIdentifier("data entity identifier")) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val type = when (val parsed = parseOptionalDataType()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val block = when (val parsed = parseDataBlock()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            return GMResult.Ok(
                EventModelingDataEntity(
                    name = name,
                    dataType = type,
                    dataBlockValue = block,
                ),
            )
        }

        fun parseNoteEntity(): GMResult<EventModelingNoteEntity, MermaidError> {
            consumeKnownKeyword("note")
            skipWhitespaceAndComments()
            val frame = when (val parsed = parseFrameIdentifier()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val type = when (val parsed = parseOptionalDataType()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            val block = when (val parsed = parseDataBlock()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            return GMResult.Ok(
                EventModelingNoteEntity(
                    sourceFrameName = frame,
                    dataType = type,
                    dataBlockValue = block,
                ),
            )
        }

        fun parseGwt(): GMResult<EventModelingGwt, MermaidError> {
            consumeKnownKeyword("gwt")
            skipWhitespaceAndComments()
            val frame = when (val parsed = parseFrameIdentifier()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipWhitespaceAndComments()
            if (!hasKeyword("given")) {
                return error("Expected 'given' in gwt definition")
            }
            consumeKnownKeyword("given")
            val given = when (val parsed = parseGwtStatements(setOf("when", "then"))) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            if (given.isEmpty()) {
                return error("Expected at least one gwt given statement")
            }
            skipTrivia()
            val whenStatements = if (hasKeyword("when")) {
                consumeKnownKeyword("when")
                when (val parsed = parseGwtStatements(setOf("then"))) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }.also { statements ->
                    if (statements.isEmpty()) {
                        return error("Expected at least one gwt when statement")
                    }
                }
            } else {
                emptyList()
            }
            skipTrivia()
            if (!hasKeyword("then")) {
                return error("Expected 'then' in gwt definition")
            }
            consumeKnownKeyword("then")
            val thenStatements = when (val parsed = parseGwtStatements(TOP_LEVEL_KEYWORDS)) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            if (thenStatements.isEmpty()) {
                return error("Expected at least one gwt then statement")
            }
            return GMResult.Ok(
                EventModelingGwt(
                    sourceFrameName = frame,
                    givenStatements = given,
                    whenStatements = whenStatements,
                    thenStatements = thenStatements,
                ),
            )
        }

        fun parseTitle(): String {
            consumeKnownKeyword("title")
            return readLineValue()
        }

        fun parseColonMetadata(keyword: String): GMResult<String, MermaidError> {
            consumeKnownKeyword(keyword)
            skipHorizontalWhitespace()
            if (!consume(":")) {
                return error("Expected ':' after $keyword")
            }
            return GMResult.Ok(readLineValue())
        }

        fun parseAccessibilityDescription(): GMResult<String, MermaidError> {
            consumeKnownKeyword("accDescr")
            skipHorizontalWhitespace()
            if (consume(":")) {
                return GMResult.Ok(readLineValue())
            }
            if (!consume("{")) {
                return error("Expected ':' or '{' after accDescr")
            }
            val opening = index - 1
            val end = source.indexOf('}', startIndex = index)
            if (end < 0) {
                return errorAt(opening, "Unterminated accDescr block")
            }
            val value = normalizeMultiline(source.substring(index, end))
            index = end + 1
            return GMResult.Ok(value)
        }

        fun parseQualifiedName(): GMResult<String, MermaidError> {
            val segments = mutableListOf<String>()
            when (val first = parseIdentifier("entity identifier")) {
                is GMResult.Ok -> segments += first.value
                is GMResult.Err -> return first
            }
            while (true) {
                skipWhitespaceAndComments()
                if (!consume(".")) {
                    break
                }
                skipWhitespaceAndComments()
                when (val next = parseIdentifier("qualified entity identifier")) {
                    is GMResult.Ok -> segments += next.value
                    is GMResult.Err -> return next
                }
            }
            return GMResult.Ok(segments.joinToString("."))
        }

        fun skipTrivia() {
            while (true) {
                skipWhitespace()
                when {
                    source.startsWith("%%", index) -> skipLineComment()
                    source.startsWith("//", index) -> skipLineComment()
                    source.startsWith("/*", index) -> skipBlockComment()
                    else -> return
                }
            }
        }

        fun skipWhitespaceAndComments() {
            skipTrivia()
        }

        fun skipHorizontalWhitespace() {
            while (source.getOrNull(index) == ' ' || source.getOrNull(index) == '\t') {
                index += 1
            }
        }

        fun error(detail: String): GMResult.Err<MermaidError> = errorAt(index, detail)

        fun errorAtEnd(detail: String): GMResult.Err<MermaidError> =
            errorAt(source.length.coerceAtLeast(1) - 1, detail)

        private fun parseGwtStatements(
            stopKeywords: Set<String>,
        ): GMResult<List<EventModelingGwtStatement>, MermaidError> {
            val statements = mutableListOf<EventModelingGwtStatement>()
            while (true) {
                skipTrivia()
                if (isAtEnd() || stopKeywords.any(::hasKeyword)) {
                    break
                }
                val type = when (val parsed = parseModelEntityType()) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                skipWhitespaceAndComments()
                val entity = when (val parsed = parseIdentifier("model entity reference")) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                statements += EventModelingGwtStatement(
                    modelEntityType = type,
                    entityIdentifier = entity,
                )
            }
            return GMResult.Ok(statements)
        }

        private fun parseModelEntityType(): GMResult<String, MermaidError> {
            val type = MODEL_ENTITY_TYPES.firstOrNull(::hasKeyword)
                ?: return error("Expected an Event Modeling entity type")
            consumeKnownKeyword(type)
            return GMResult.Ok(type)
        }

        private fun parseFrameIdentifier(): GMResult<String, MermaidError> {
            val start = index
            while (source.getOrNull(index)?.isDigit() == true) {
                index += 1
            }
            val value = source.substring(start, index)
            if (value.length !in 1..3) {
                return errorAt(start, "Expected a one-to-three digit frame identifier")
            }
            return GMResult.Ok(value)
        }

        private fun parseIdentifier(label: String): GMResult<String, MermaidError> {
            val start = index
            val first = source.getOrNull(index)
            if (first == null || !(first == '_' || first.isAsciiLetter())) {
                return error("Expected $label")
            }
            index += 1
            while (source.getOrNull(index).isIdentifierPart()) {
                index += 1
            }
            return GMResult.Ok(source.substring(start, index))
        }

        private fun parseOptionalTypedInlineData():
            GMResult<Pair<String?, String>?, MermaidError> {
            val dataStart = index
            val type = when (val parsed = parseOptionalDataType()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            if (type != null) {
                skipWhitespaceAndComments()
            }
            val opening = source.getOrNull(index)
            if (opening !in setOf('{', '"', '\'')) {
                index = dataStart
                return GMResult.Ok(null)
            }
            val delimiter = when (opening) {
                '{' -> '}'
                '"', '\'' -> opening
                else -> return GMResult.Ok(null)
            }
            val lineEnd = source.indexOf('\n', startIndex = index)
                .let { found -> if (found < 0) source.length else found }
            val closing = source.lastIndexOf(delimiter, startIndex = lineEnd - 1)
            if (closing < index) {
                return error("Unterminated inline data")
            }
            val value = source.substring(index, closing + 1)
            index = closing + 1
            return GMResult.Ok(type to value)
        }

        private fun parseOptionalDataType(): GMResult<String?, MermaidError> {
            val opening = index
            if (!consume("`")) {
                return GMResult.Ok(null)
            }
            skipWhitespaceAndComments()
            val typeStart = index
            val type = when (val parsed = parseIdentifier("Event Modeling data type")) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            if (type !in DATA_TYPES) {
                return errorAt(typeStart, "Unsupported Event Modeling data type '$type'")
            }
            skipWhitespaceAndComments()
            if (!consume("`")) {
                return errorAt(opening, "Unterminated Event Modeling data type")
            }
            return GMResult.Ok(type)
        }

        private fun parseDataBlock(): GMResult<String, MermaidError> {
            val opening = index
            if (!consume("{")) {
                return error("Expected an Event Modeling data block")
            }
            skipHorizontalWhitespace()
            if (!consume("\n")) {
                return errorAt(opening, "Event Modeling data blocks must start on a new line")
            }
            var closing = index
            while (closing < source.length) {
                closing = source.indexOf('}', startIndex = closing)
                if (closing < 0) {
                    return errorAt(opening, "Unterminated Event Modeling data block")
                }
                val followsLineBreak = source.getOrNull(closing - 1) == '\n'
                val suffix = source.getOrNull(closing + 1)
                if (followsLineBreak && (suffix == null || suffix.isWhitespace())) {
                    val value = source.substring(opening, closing + 1)
                    index = closing + 1
                    return GMResult.Ok(value)
                }
                closing += 1
            }
            return errorAt(opening, "Unterminated Event Modeling data block")
        }

        private fun readLineValue(): String {
            skipHorizontalWhitespace()
            val end = source.indexOf('\n', startIndex = index)
                .let { found -> if (found < 0) source.length else found }
            val value = source.substring(index, end)
                .substringBefore("%%")
                .trim()
            index = end
            return value
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) {
                index += 1
            }
        }

        private fun skipLineComment() {
            val end = source.indexOf('\n', startIndex = index)
            index = if (end < 0) source.length else end + 1
        }

        private fun skipBlockComment() {
            val opening = index
            val end = source.indexOf("*/", startIndex = opening + 2)
            if (end < 0) {
                unterminatedBlockCommentIndex = opening
                index = source.length
            } else {
                index = end + 2
            }
        }

        private fun consume(token: String): Boolean {
            if (!source.startsWith(token, index)) {
                return false
            }
            index += token.length
            return true
        }

        fun errorAt(
            sourceIndex: Int,
            detail: String,
        ): GMResult.Err<MermaidError> {
            val bounded = sourceIndex.coerceIn(0, source.length)
            val before = source.substring(0, bounded)
            val line = before.count { character -> character == '\n' } + 1 + lineOffset
            val lastBreak = before.lastIndexOf('\n')
            val column = bounded - lastBreak
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Parse error on line $line, column $column: $detail",
                ),
            )
        }

        private fun normalizeMultiline(value: String): String =
            value.lines().joinToString("\n") { line -> line.trim() }.trim()

        private fun Char?.isIdentifierPart(): Boolean =
            this != null && (this == '_' || this.isAsciiLetter() || this.isDigit())

        private fun Char.isAsciiLetter(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z'

    }

    private companion object {
        const val HEADER = "eventmodeling"
        val MODEL_ENTITY_TYPES = listOf(
            "readmodel",
            "processor",
            "command",
            "event",
            "rmo",
            "pcr",
            "cmd",
            "evt",
            "ui",
        )
        val DATA_TYPES = setOf("json", "jsobj", "figma", "salt", "uri", "md", "html", "text")
        val TOP_LEVEL_KEYWORDS = setOf(
            "accDescr",
            "accTitle",
            "title",
            "tf",
            "timeframe",
            "rf",
            "resetframe",
            "entity",
            "data",
            "note",
            "gwt",
        )
    }
}
