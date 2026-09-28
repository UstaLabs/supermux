package com.swithun.cmpmermaid.core.usecase.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/usecase/parser/usecase.tokens.ts
 * packages/mermaid/src/diagrams/usecase/parser/usecase.parser.ts
 * packages/mermaid/src/diagrams/usecase/parser/usecase.visitor.ts.
 */
internal class UsecaseParser(
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<UsecaseDocument, MermaidError> {
        val statements = when (val result = scanStatements(source)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val headerIndex = statements.indexOfFirst { statement ->
            statement.text.isNotBlank() && !statement.text.trimStart().startsWith(COMMENT_PREFIX)
        }
        if (headerIndex < 0 || statements[headerIndex].text.trim() != HEADER) {
            val location = statements.getOrNull(headerIndex.coerceAtLeast(0))
            return parseError(
                line = location?.line ?: 1,
                column = location?.column ?: 1,
                message = "Expected '$HEADER' diagram header",
            )
        }

        val builder = UsecaseModelBuilder(diagramTitle)
        var parentBoundary: ParsedBoundary? = null
        for (index in (headerIndex + 1) until statements.size) {
            val statement = statements[index]
            val trimmed = statement.text.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) continue

            if (trimmed == END_KEYWORD) {
                if (parentBoundary == null) {
                    return parseError(statement, "Unexpected 'end' outside system boundary")
                }
                parentBoundary = null
                continue
            }
            if (parentBoundary != null) {
                when {
                    trimmed.startsWith("$ACTOR_KEYWORD ") -> when (
                        val result = parseActorStatement(statement, builder, parentBoundary)
                    ) {
                        is GMResult.Ok -> {
                            if (result.value) {
                                return parseError(
                                    statement,
                                    "Relationships are not allowed inside system boundaries",
                                )
                            }
                        }
                        is GMResult.Err -> return result
                    }
                    trimmed.startsWith("$SYSTEM_BOUNDARY_KEYWORD ") ->
                        return parseError(
                            statement,
                            "Nested system boundaries are not supported",
                        )
                    else -> when (
                        val result = parseEntityStatement(statement, builder, parentBoundary)
                    ) {
                        is GMResult.Ok -> {
                            if (result.value) {
                                return parseError(
                                    statement,
                                    "Relationships are not allowed inside system boundaries",
                                )
                            }
                        }
                        is GMResult.Err -> return result
                    }
                }
                continue
            }

            when {
                trimmed.startsWith("accTitle") -> when (
                    val result = parseAccessibilityTitle(statement)
                ) {
                    is GMResult.Ok -> builder.setAccessibilityTitle(result.value)
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("accDescr") -> when (
                    val result = parseAccessibilityDescription(statement)
                ) {
                    is GMResult.Ok -> builder.setAccessibilityDescription(result.value)
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$DIRECTION_KEYWORD ") -> when (
                    val result = parseDirection(statement)
                ) {
                    is GMResult.Ok -> builder.setDirection(result.value)
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$ACTOR_KEYWORD ") -> when (
                    val result = parseActorStatement(statement, builder, parent = null)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$SYSTEM_BOUNDARY_KEYWORD ") -> when (
                    val result = parseBoundary(statement, builder)
                ) {
                    is GMResult.Ok -> parentBoundary = result.value
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$NOTE_KEYWORD ") -> when (
                    val result = parseNote(statement, builder)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$JSON_KEYWORD ") -> when (
                    val result = parseJson(statement, builder)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$CLASS_DEF_KEYWORD ") -> when (
                    val result = parseClassDefinition(statement, builder)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$CLASS_KEYWORD ") -> when (
                    val result = parseClassAssignment(statement, builder)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                trimmed.startsWith("$STYLE_KEYWORD ") -> when (
                    val result = parseStyleAssignment(statement, builder)
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                FORBIDDEN_PLANT_UML.any { keyword -> trimmed.startsWithKeyword(keyword) } ->
                    return parseError(statement, "Unsupported PlantUML use-case statement")
                else -> when (
                    val result = parseMetadataAssignment(statement, builder)
                ) {
                    is GMResult.Ok -> if (!result.value) {
                        when (val entity = parseEntityStatement(statement, builder, parent = null)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return entity
                        }
                    }
                    is GMResult.Err -> return result
                }
            }
        }
        if (parentBoundary != null) {
            return parseError(
                parentBoundary.location.line,
                parentBoundary.location.column,
                "System boundary '${parentBoundary.id}' is missing 'end'",
            )
        }
        return builder.finalizeDocument()
    }

    private fun parseActorStatement(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
        parent: ParsedBoundary?,
    ): GMResult<Boolean, MermaidError> {
        val cursor = SourceCursor(statement)
        if (!cursor.consumeKeyword(ACTOR_KEYWORD)) {
            return parseError(statement, "Expected 'actor'")
        }
        val actors = mutableListOf<ParsedNode>()
        while (true) {
            val actor = when (val result = parseNode(cursor, actor = true)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            actors += actor
            builder.addElement(actor.toDraft(DraftElementKind.Actor, parent, declaration = true))
            cursor.skipWhitespace()
            if (!cursor.consume(',')) break
        }
        cursor.skipWhitespace()
        if (cursor.isAtEnd()) return GMResult.Ok(false)
        if (actors.size != 1) {
            return parseError(cursor, "An actor list cannot also declare a relationship")
        }
        val relationship = when (
            val result = parseRelationshipTail(
                cursor = cursor,
                source = actors.first().toEndpoint(declaration = true),
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        builder.addRelationship(relationship)
        return GMResult.Ok(true)
    }

    private fun parseEntityStatement(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
        parent: ParsedBoundary?,
    ): GMResult<Boolean, MermaidError> {
        val cursor = SourceCursor(statement)
        val entity = when (val result = parseNode(cursor, actor = false)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        cursor.skipWhitespace()
        if (cursor.isAtEnd()) {
            builder.addElement(
                entity.copy(
                    shape = entity.shape ?: UsecaseShape.Ellipse,
                    explicitDeclaration = true,
                ).toDraft(DraftElementKind.Usecase, parent, declaration = true),
            )
            return GMResult.Ok(false)
        }
        if (entity.explicitDeclaration) {
            builder.addElement(entity.toDraft(DraftElementKind.Usecase, parent, declaration = true))
        }
        val relationship = when (
            val result = parseRelationshipTail(
                cursor = cursor,
                source = entity.toEndpoint(entity.explicitDeclaration),
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        builder.addRelationship(relationship)
        return GMResult.Ok(true)
    }

    private fun parseRelationshipTail(
        cursor: SourceCursor,
        source: DraftEndpoint,
    ): GMResult<DraftRelationship, MermaidError> {
        cursor.skipWhitespace()
        val relationLocation = cursor.location()
        var explicitId: String? = null
        var explicitLocation: DraftLocation? = null
        val checkpoint = cursor.position
        val possibleId = cursor.consumeIdentifier()
        if (possibleId != null) {
            cursor.skipWhitespace()
            if (cursor.consume('@')) {
                explicitId = possibleId.value
                explicitLocation = possibleId.location
            } else {
                cursor.position = checkpoint
            }
        }
        cursor.skipWhitespace()
        val arrow = when (val result = parseArrow(cursor)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        cursor.skipWhitespace()
        val target = when (val result = parseNode(cursor, actor = false)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) {
            return parseError(cursor, "Unexpected text after relationship target")
        }
        return GMResult.Ok(
            DraftRelationship(
                source = source,
                target = target.toEndpoint(target.explicitDeclaration),
                location = relationLocation,
                explicitId = explicitId,
                explicitIdLocation = explicitLocation,
                type = arrow.type,
                arrowType = arrow.arrowType,
                label = arrow.label,
                minimumLength = arrow.minimumLength,
            ),
        )
    }

    private fun parseArrow(cursor: SourceCursor): GMResult<ParsedArrow, MermaidError> {
        cursor.skipWhitespace()
        if (cursor.consume("..>")) {
            cursor.skipWhitespace()
            if (!cursor.consume(':')) return parseError(cursor, "Expected ':' after '..>'")
            cursor.skipWhitespace()
            val type = when {
                cursor.consumeKeyword("include", ignoreCase = true) ->
                    UsecaseRelationshipType.Include
                cursor.consumeKeyword("extend", ignoreCase = true) ->
                    UsecaseRelationshipType.Extend
                else -> return parseError(cursor, "Expected 'include' or 'extend'")
            }
            return GMResult.Ok(
                ParsedArrow(
                    type = type,
                    arrowType = UsecaseArrowType.SolidArrow,
                    label = UsecaseLabel(type.name.lowercase(), UsecaseLabelType.Text),
                    minimumLength = 1,
                ),
            )
        }
        if (cursor.consume("--|>")) {
            return GMResult.Ok(
                ParsedArrow(
                    UsecaseRelationshipType.Generalization,
                    UsecaseArrowType.SolidArrow,
                    minimumLength = 1,
                ),
            )
        }

        val operator = cursor.consumeAssociationOperator()
            ?: return parseError(cursor, "Expected a use-case relationship operator")
        val direct = when (operator.kind) {
            AssociationOperatorKind.ForwardSolid -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.SolidArrow,
                minimumLength = operator.dashes - 1,
            )
            AssociationOperatorKind.ForwardCircle -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.CircleArrow,
                minimumLength = 1,
            )
            AssociationOperatorKind.ForwardCross -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.CrossArrow,
                minimumLength = 1,
            )
            AssociationOperatorKind.BackwardSolid -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.BackArrow,
                minimumLength = operator.dashes - 1,
            )
            AssociationOperatorKind.BackwardCircle -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.CircleArrowReversed,
                minimumLength = 1,
            )
            AssociationOperatorKind.BackwardCross -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.CrossArrowReversed,
                minimumLength = 1,
            )
            AssociationOperatorKind.Markerless -> ParsedArrow(
                UsecaseRelationshipType.Association,
                UsecaseArrowType.LineSolid,
                minimumLength = operator.dashes - 1,
            )
        }
        val mayHaveLabel = operator.kind in setOf(
            AssociationOperatorKind.Markerless,
            AssociationOperatorKind.BackwardSolid,
            AssociationOperatorKind.BackwardCircle,
            AssociationOperatorKind.BackwardCross,
        )
        if (!mayHaveLabel) return GMResult.Ok(direct)

        val closing = cursor.findClosingAssociationOperator()
            ?: return GMResult.Ok(direct)
        val labelSource = cursor.source.substring(cursor.position, closing.start).trim()
        if (labelSource.isEmpty()) return GMResult.Ok(direct)
        val label = when (
            val result = parseStandaloneLabel(labelSource, cursor.location())
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        cursor.position = closing.endExclusive
        val arrowType = when (closing.kind) {
            AssociationOperatorKind.ForwardSolid -> UsecaseArrowType.SolidArrow
            AssociationOperatorKind.ForwardCircle -> UsecaseArrowType.CircleArrow
            AssociationOperatorKind.ForwardCross -> UsecaseArrowType.CrossArrow
            AssociationOperatorKind.Markerless -> direct.arrowType
            else -> return parseError(cursor, "Invalid closing relationship operator")
        }
        val minimumLength = if (
            arrowType in setOf(UsecaseArrowType.SolidArrow, UsecaseArrowType.LineSolid)
        ) {
            closing.dashes - 1
        } else {
            1
        }
        return GMResult.Ok(direct.copy(arrowType = arrowType, label = label, minimumLength = minimumLength))
    }

    private fun parseNode(
        cursor: SourceCursor,
        actor: Boolean,
    ): GMResult<ParsedNode, MermaidError> {
        cursor.skipWhitespace()
        val start = cursor.location()
        val identifier = cursor.consumeIdentifier()
        val base = if (identifier != null) {
            var label = UsecaseLabel(identifier.value, UsecaseLabelType.Text)
            var shape: UsecaseShape? = null
            cursor.skipWhitespace()
            when {
                cursor.peek() == '(' -> {
                    val parsed = when (val result = cursor.consumeDelimitedLabel('(', ')')) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    label = parsed
                    if (!actor) shape = UsecaseShape.Ellipse
                }
                !actor && cursor.peek() == '[' -> {
                    val parsed = when (val result = cursor.consumeDelimitedLabel('[', ']')) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    label = parsed
                    shape = UsecaseShape.Rectangle
                }
            }
            ParsedNode(
                id = identifier.value,
                label = label,
                location = identifier.location,
                generated = false,
                shape = shape,
                explicitDeclaration = actor || shape != null,
            )
        } else {
            val parsed = when (val result = cursor.consumeQuotedLabel()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            ParsedNode(
                id = generatedId(parsed.text),
                label = parsed,
                location = start,
                generated = true,
                explicitDeclaration = actor,
            )
        }

        cursor.skipWhitespace()
        val metadata = if (cursor.startsWith("@{")) {
            when (val result = cursor.consumeMetadata()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            null
        }
        cursor.skipWhitespace()
        val stereotype = if (cursor.startsWith("<<")) {
            when (val result = cursor.consumeStereotype()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            null
        }
        cursor.skipWhitespace()
        val classes = if (cursor.startsWith(":::")) {
            when (val result = cursor.consumeClasses()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            emptyList()
        }
        return GMResult.Ok(
            base.copy(
                metadata = metadata,
                stereotype = stereotype,
                classes = classes,
                explicitDeclaration = base.explicitDeclaration ||
                    metadata != null ||
                    stereotype != null,
            ),
        )
    }

    private fun parseBoundary(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<ParsedBoundary, MermaidError> {
        val cursor = SourceCursor(statement)
        if (!cursor.consumeKeyword(SYSTEM_BOUNDARY_KEYWORD)) {
            return parseError(statement, "Expected 'systemBoundary'")
        }
        cursor.skipWhitespace()
        val start = cursor.location()
        val identifier = cursor.consumeIdentifier()
        val id: String
        val label: UsecaseLabel
        val generated: Boolean
        if (identifier != null) {
            id = identifier.value
            generated = false
            cursor.skipWhitespace()
            label = if (cursor.peek() == '(' || cursor.peek() == '[') {
                val opening = cursor.peek() ?: return parseError(
                    cursor,
                    "Expected a system boundary label",
                )
                when (
                    val result = cursor.consumeDelimitedLabel(
                        opening = opening,
                        closing = if (opening == '(') ')' else ']',
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
            } else {
                UsecaseLabel(id, UsecaseLabelType.Text)
            }
        } else {
            label = when (val result = cursor.consumeQuotedLabel()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            id = generatedId(label.text)
            generated = true
        }
        cursor.skipWhitespace()
        val metadata = if (cursor.startsWith("@{")) {
            when (val result = cursor.consumeMetadata()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            null
        }
        cursor.skipWhitespace()
        val classes = if (cursor.startsWith(":::")) {
            when (val result = cursor.consumeClasses()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            emptyList()
        }
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) {
            return parseError(cursor, "Unexpected text after system boundary declaration")
        }
        val parsed = ParsedBoundary(id, start, label)
        builder.addBoundary(
            DraftBoundary(
                id = id,
                label = label,
                location = start,
                generated = generated,
                metadata = metadata,
                classes = classes,
            ),
        )
        return GMResult.Ok(parsed)
    }

    private fun parseNote(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Unit, MermaidError> {
        val cursor = SourceCursor(statement)
        if (!cursor.consumeKeyword(NOTE_KEYWORD) || !cursor.consumeKeyword(FOR_KEYWORD)) {
            return parseError(statement, "Expected 'note for'")
        }
        cursor.skipWhitespace()
        val target = cursor.consumeIdentifier()
            ?: return parseError(cursor, "Expected a note target identifier")
        cursor.skipWhitespace()
        val labelSource = cursor.remaining().trim()
        if (labelSource.isEmpty()) return parseError(cursor, "Expected a note label")
        val label = when (val result = parseStandaloneLabel(labelSource, cursor.location())) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        builder.addNote(
            DraftNote(
                target = target.value,
                targetLocation = target.location,
                label = label,
                location = statement.location(),
            ),
        )
        return GMResult.Ok(Unit)
    }

    private fun parseJson(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Unit, MermaidError> {
        val cursor = SourceCursor(statement)
        if (!cursor.consumeKeyword(JSON_KEYWORD)) {
            return parseError(statement, "Expected 'json'")
        }
        cursor.skipWhitespace()
        val id = cursor.consumeIdentifier()
            ?: return parseError(cursor, "Expected a JSON node identifier")
        cursor.skipWhitespace()
        if (!cursor.consume('@')) return parseError(cursor, "Expected '@' before JSON object")
        cursor.skipWhitespace()
        val jsonStart = cursor.location()
        val jsonSource = when (val result = cursor.consumeBalancedBraces()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val parsed = try {
            Json.parseToJsonElement(jsonSource)
        } catch (failure: Exception) {
            return parseError(
                jsonStart.line,
                jsonStart.column,
                "Invalid JSON object: ${failure.message ?: "invalid JSON"}",
            )
        }
        val value = parsed as? JsonObject
            ?: return parseError(jsonStart.line, jsonStart.column, "JSON node root must be an object")
        cursor.skipWhitespace()
        val classes = if (cursor.startsWith(":::")) {
            when (val result = cursor.consumeClasses()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        } else {
            emptyList()
        }
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) return parseError(cursor, "Unexpected text after JSON declaration")
        builder.addJson(DraftJson(id.value, value, id.location, classes))
        return GMResult.Ok(Unit)
    }

    private fun parseMetadataAssignment(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Boolean, MermaidError> {
        val cursor = SourceCursor(statement)
        cursor.skipWhitespace()
        val targetStart = cursor.location()
        val identifier = cursor.consumeIdentifier()
        val target = if (identifier != null) {
            identifier.value
        } else if (cursor.peek() == '"' || cursor.peek() == '\'') {
            val label = when (val result = cursor.consumeQuotedLabel()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            generatedId(label.text)
        } else {
            return GMResult.Ok(false)
        }
        cursor.skipWhitespace()
        if (!cursor.startsWith("@{")) return GMResult.Ok(false)
        val metadata = when (val result = cursor.consumeMetadata()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) {
            return parseError(cursor, "Unexpected text after metadata assignment")
        }
        builder.addMetadataAssignment(
            DraftMetadataAssignment(
                target = target,
                location = targetStart,
                metadata = metadata,
            ),
        )
        return GMResult.Ok(true)
    }

    private fun parseClassDefinition(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Unit, MermaidError> {
        val match = CLASS_DEF.matchEntire(statement.text.trim())
            ?: return parseError(statement, "Invalid classDef statement")
        val ids = parseIdentifierList(match.groupValues[1])
            ?: return parseError(statement, "Invalid classDef identifier list")
        val styles = when (val result = parseStyles(match.groupValues[2], statement)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        builder.addClassDefinition(DraftClassDefinition(ids, styles))
        return GMResult.Ok(Unit)
    }

    private fun parseClassAssignment(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Unit, MermaidError> {
        val match = CLASS_ASSIGNMENT.matchEntire(statement.text.trim())
            ?: return parseError(statement, "Invalid class statement")
        val targetIds = parseIdentifierList(match.groupValues[1])
            ?: return parseError(statement, "Invalid class target list")
        val classes = parseIdentifierList(match.groupValues[2])
            ?: return parseError(statement, "Invalid class name list")
        builder.addClassAssignment(
            DraftClassAssignment(
                targets = targetIds.map { id -> DraftTarget(id, statement.location()) },
                classes = classes,
            ),
        )
        return GMResult.Ok(Unit)
    }

    private fun parseStyleAssignment(
        statement: LogicalStatement,
        builder: UsecaseModelBuilder,
    ): GMResult<Unit, MermaidError> {
        val match = STYLE_ASSIGNMENT.matchEntire(statement.text.trim())
            ?: return parseError(statement, "Invalid style statement")
        val styles = when (val result = parseStyles(match.groupValues[2], statement)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        builder.addStyleAssignment(
            DraftStyleAssignment(
                target = match.groupValues[1],
                location = statement.location(),
                styles = styles,
            ),
        )
        return GMResult.Ok(Unit)
    }

    private fun parseStyles(
        source: String,
        statement: LogicalStatement,
    ): GMResult<List<String>, MermaidError> {
        if (';' in source) {
            return parseError(statement, "Semicolons are not valid use-case style separators")
        }
        val styles = splitEscapedCommas(source)
        if (styles.isEmpty() || styles.any { style -> ':' !in style }) {
            return parseError(statement, "Expected comma-separated CSS property:value styles")
        }
        return GMResult.Ok(styles)
    }

    private fun parseDirection(
        statement: LogicalStatement,
    ): GMResult<UsecaseDirection, MermaidError> {
        val match = DIRECTION.matchEntire(statement.text.trim())
            ?: return parseError(statement, "Invalid use-case direction")
        return when (match.groupValues[1]) {
            "TD", "TB" -> GMResult.Ok(UsecaseDirection.TopToBottom)
            "BT" -> GMResult.Ok(UsecaseDirection.BottomToTop)
            "LR" -> GMResult.Ok(UsecaseDirection.LeftToRight)
            "RL" -> GMResult.Ok(UsecaseDirection.RightToLeft)
            else -> parseError(statement, "Invalid use-case direction")
        }
    }

    private fun parseAccessibilityTitle(
        statement: LogicalStatement,
    ): GMResult<String, MermaidError> {
        val match = ACCESSIBILITY_TITLE.matchEntire(statement.text.trim())
            ?: return parseError(statement, "Expected ':' after accTitle")
        return GMResult.Ok(match.groupValues[1].trim())
    }

    private fun parseAccessibilityDescription(
        statement: LogicalStatement,
    ): GMResult<String, MermaidError> {
        val trimmed = statement.text.trim()
        val lineMatch = ACCESSIBILITY_DESCRIPTION.matchEntire(trimmed)
        if (lineMatch != null) return GMResult.Ok(lineMatch.groupValues[1].trim())
        if (trimmed.startsWith("accDescr")) {
            val opening = trimmed.indexOf('{')
            val closing = trimmed.lastIndexOf('}')
            if (opening >= 0 && closing > opening) {
                return GMResult.Ok(trimmed.substring(opening + 1, closing).trim())
            }
        }
        return parseError(statement, "Expected ':' or '{' after accDescr")
    }

    private fun parseStandaloneLabel(
        source: String,
        location: DraftLocation,
    ): GMResult<UsecaseLabel, MermaidError> {
        val value = source.trim()
        return when {
            value.startsWith("\"`") -> {
                if (!value.endsWith("`\"") || value.length < 4) {
                    parseError(location.line, location.column, "Unterminated Markdown string")
                } else {
                    GMResult.Ok(
                        UsecaseLabel(
                            value.substring(2, value.length - 2),
                            UsecaseLabelType.Markdown,
                        ),
                    )
                }
            }
            value.startsWith('"') || value.startsWith('\'') -> {
                val quote = value.first()
                if (value.length < 2 || value.last() != quote) {
                    parseError(location.line, location.column, "Unterminated quoted string")
                } else {
                    GMResult.Ok(
                        UsecaseLabel(value.substring(1, value.length - 1), UsecaseLabelType.Text),
                    )
                }
            }
            value.isEmpty() ->
                parseError(location.line, location.column, "Expected a label")
            else -> GMResult.Ok(UsecaseLabel(value, UsecaseLabelType.Text))
        }
    }

    private fun scanStatements(
        source: String,
    ): GMResult<List<LogicalStatement>, MermaidError> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val statements = mutableListOf<LogicalStatement>()
        var start = 0
        var line = 1
        var startLine = 1
        var braceDepth = 0
        var markdown = false
        var jsonString = false
        var escaped = false
        var index = 0
        while (index <= normalized.length) {
            val atEnd = index == normalized.length
            val character = normalized.getOrNull(index)
            val next = normalized.getOrNull(index + 1)
            if (!atEnd) {
                if (markdown) {
                    if (character == '`' && next == '"') {
                        markdown = false
                        index += 1
                    }
                } else if (jsonString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> jsonString = false
                    }
                } else {
                    when {
                        character == '"' && next == '`' -> {
                            markdown = true
                            index += 1
                        }
                        braceDepth > 0 && character == '"' -> jsonString = true
                        character == '{' -> braceDepth += 1
                        character == '}' -> braceDepth -= 1
                    }
                    if (braceDepth < 0) {
                        return parseError(line, 1, "Unexpected closing brace")
                    }
                }
            }
            if (atEnd || (character == '\n' && braceDepth == 0 && !markdown)) {
                val text = normalized.substring(start, index)
                val first = text.indexOfFirst { value -> value != ' ' && value != '\t' }
                statements += LogicalStatement(
                    text = text,
                    line = startLine,
                    column = if (first < 0) 1 else first + 1,
                    offset = start,
                )
                start = index + 1
                startLine = line + 1
            }
            if (character == '\n') line += 1
            index += 1
        }
        if (markdown) {
            return parseError(startLine, 1, "Unterminated Markdown string")
        }
        if (braceDepth > 0) {
            return parseError(startLine, 1, "Unterminated brace block")
        }
        return GMResult.Ok(statements)
    }

    private fun splitEscapedCommas(source: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        source.forEach { character ->
            when {
                escaped && character == ',' -> {
                    current.append(',')
                    escaped = false
                }
                escaped -> {
                    current.append('\\')
                    current.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == ',' -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun parseIdentifierList(source: String): List<String>? {
        val result = source.split(',').map(String::trim)
        return result.takeIf { values ->
            values.isNotEmpty() && values.all(IDENTIFIER::matches)
        }
    }

    private fun generatedId(label: String): String = buildString(label.length) {
        label.forEach { character ->
            append(if (character.isAsciiWord()) character else '_')
        }
    }

    private fun String.startsWithKeyword(keyword: String): Boolean =
        this == keyword || (
            startsWith(keyword) &&
                getOrNull(keyword.length)?.let { next -> next == ' ' || next == '\t' } == true
            )

    private fun parseError(
        statement: LogicalStatement,
        message: String,
    ): GMResult.Err<MermaidError> = parseError(statement.line, statement.column, message)

    private fun parseError(
        cursor: SourceCursor,
        message: String,
    ): GMResult.Err<MermaidError> {
        val location = cursor.location()
        return parseError(location.line, location.column, message)
    }

    private fun parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(line + lineOffset, column, message),
    )

    private data class LogicalStatement(
        val text: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) {
        fun location(): DraftLocation = DraftLocation(line, column, offset)
    }

    private data class ParsedBoundary(
        val id: String,
        val location: DraftLocation,
        val label: UsecaseLabel,
    )

    private data class ParsedNode(
        val id: String,
        val label: UsecaseLabel,
        val location: DraftLocation,
        val generated: Boolean,
        val shape: UsecaseShape? = null,
        val metadata: DraftMetadata? = null,
        val stereotype: String? = null,
        val classes: List<String> = emptyList(),
        val explicitDeclaration: Boolean = false,
    ) {
        fun toDraft(
            kind: DraftElementKind,
            parent: ParsedBoundary?,
            declaration: Boolean,
        ): DraftElement = DraftElement(
            id = id,
            kind = kind,
            label = label,
            location = location,
            generated = generated,
            parentId = parent?.id,
            parentLocation = parent?.location,
            shape = shape,
            metadata = metadata,
            stereotype = stereotype,
            classes = classes,
        )

        fun toEndpoint(declaration: Boolean): DraftEndpoint = DraftEndpoint(
            id = id,
            label = label,
            location = location,
            generated = generated,
            declaration = declaration,
            classesOnReference = classes.isNotEmpty(),
        )
    }

    private data class ParsedArrow(
        val type: UsecaseRelationshipType,
        val arrowType: UsecaseArrowType,
        val label: UsecaseLabel? = null,
        val minimumLength: Int,
    )

    private data class LocatedIdentifier(
        val value: String,
        val location: DraftLocation,
    )

    private enum class AssociationOperatorKind {
        ForwardSolid,
        BackwardSolid,
        Markerless,
        ForwardCircle,
        BackwardCircle,
        ForwardCross,
        BackwardCross,
    }

    private data class AssociationOperator(
        val kind: AssociationOperatorKind,
        val dashes: Int,
        val start: Int,
        val endExclusive: Int,
    )

    private inner class SourceCursor(
        private val statement: LogicalStatement,
    ) {
        val source: String = statement.text
        var position: Int = 0

        fun isAtEnd(): Boolean = position >= source.length

        fun remaining(): String = source.substring(position)

        fun peek(): Char? = source.getOrNull(position)

        fun startsWith(value: String): Boolean = source.startsWith(value, position)

        fun skipWhitespace() {
            while (source.getOrNull(position)?.isWhitespace() == true) position += 1
        }

        fun consume(value: Char): Boolean {
            if (source.getOrNull(position) != value) return false
            position += 1
            return true
        }

        fun consume(value: String): Boolean {
            if (!source.startsWith(value, position)) return false
            position += value.length
            return true
        }

        fun consumeKeyword(
            value: String,
            ignoreCase: Boolean = false,
        ): Boolean {
            skipWhitespace()
            if (!source.regionMatches(position, value, 0, value.length, ignoreCase)) return false
            val next = source.getOrNull(position + value.length)
            if (next != null && next.isAsciiWord()) return false
            position += value.length
            return true
        }

        fun consumeIdentifier(): LocatedIdentifier? {
            skipWhitespace()
            val start = position
            while (source.getOrNull(position)?.isAsciiWord() == true) position += 1
            if (position == start) return null
            return LocatedIdentifier(
                value = source.substring(start, position),
                location = location(start),
            )
        }

        fun consumeQuotedLabel(): GMResult<UsecaseLabel, MermaidError> {
            skipWhitespace()
            val start = position
            if (startsWith("\"`")) {
                position += 2
                val end = source.indexOf("`\"", position)
                if (end < 0) return parseError(this, "Unterminated Markdown string")
                val text = source.substring(position, end)
                position = end + 2
                return GMResult.Ok(UsecaseLabel(text, UsecaseLabelType.Markdown))
            }
            val quote = peek()
            if (quote != '"' && quote != '\'') {
                return parseError(this, "Expected an identifier or quoted label")
            }
            position += 1
            val end = source.indexOf(quote, position)
            if (end < 0 || '\n' in source.substring(position, end)) {
                position = start
                return parseError(this, "Unterminated quoted string")
            }
            val text = source.substring(position, end)
            position = end + 1
            return GMResult.Ok(UsecaseLabel(text, UsecaseLabelType.Text))
        }

        fun consumeDelimitedLabel(
            opening: Char,
            closing: Char,
        ): GMResult<UsecaseLabel, MermaidError> {
            skipWhitespace()
            if (!consume(opening)) return parseError(this, "Expected '$opening'")
            skipWhitespace()
            val label = if (startsWith("\"`") || peek() == '"' || peek() == '\'') {
                when (val result = consumeQuotedLabel()) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
            } else {
                val start = position
                val end = source.indexOf(closing, start)
                if (end < 0 || '\n' in source.substring(start, end)) {
                    return parseError(this, "Unterminated '$opening' label")
                }
                position = end
                UsecaseLabel(source.substring(start, end).trim(), UsecaseLabelType.Text)
            }
            skipWhitespace()
            if (!consume(closing)) return parseError(this, "Expected '$closing'")
            return GMResult.Ok(label)
        }

        fun consumeMetadata(): GMResult<DraftMetadata, MermaidError> {
            skipWhitespace()
            val start = location()
            if (!consume('@') || !consume('{')) return parseError(this, "Expected '@{'")
            val contentStart = position
            var quoted: Char? = null
            while (!isAtEnd()) {
                val current = peek()
                if (quoted != null) {
                    if (current == quoted) quoted = null
                    position += 1
                    continue
                }
                when (current) {
                    '"', '\'' -> {
                        quoted = current
                        position += 1
                    }
                    '}' -> {
                        val content = source.substring(contentStart, position)
                        position += 1
                        return parseMetadataProperties(content, start)
                    }
                    else -> position += 1
                }
            }
            return parseError(this, "Unterminated metadata block")
        }

        fun consumeStereotype(): GMResult<String, MermaidError> {
            skipWhitespace()
            if (!consume("<<")) return parseError(this, "Expected '<<'")
            val end = source.indexOf(">>", position)
            if (end < 0 || '\n' in source.substring(position, end)) {
                return parseError(this, "Unterminated stereotype")
            }
            val value = source.substring(position, end).trim()
            if (value.isEmpty()) return parseError(this, "Stereotype must not be empty")
            position = end + 2
            return GMResult.Ok(value)
        }

        fun consumeClasses(): GMResult<List<String>, MermaidError> {
            skipWhitespace()
            if (!consume(":::")) return parseError(this, "Expected ':::'")
            val classes = mutableListOf<String>()
            while (true) {
                val identifier = consumeIdentifier()
                    ?: return parseError(this, "Expected a class name after ':::'")
                classes += identifier.value
                skipWhitespace()
                if (!consume(',')) break
            }
            return GMResult.Ok(classes)
        }

        fun consumeBalancedBraces(): GMResult<String, MermaidError> {
            skipWhitespace()
            val start = position
            if (!consume('{')) return parseError(this, "Expected JSON object")
            var depth = 1
            var quoted = false
            var escaped = false
            while (!isAtEnd()) {
                val current = peek()
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == '"' -> quoted = false
                    }
                } else {
                    when (current) {
                        '"' -> quoted = true
                        '{' -> depth += 1
                        '}' -> {
                            depth -= 1
                            if (depth == 0) {
                                position += 1
                                return GMResult.Ok(source.substring(start, position))
                            }
                        }
                    }
                }
                position += 1
            }
            return parseError(this, "Unterminated JSON object")
        }

        fun consumeAssociationOperator(): AssociationOperator? {
            skipWhitespace()
            val start = position
            if (startsWith("o--")) {
                position += 3
                return AssociationOperator(
                    AssociationOperatorKind.BackwardCircle,
                    2,
                    start,
                    position,
                )
            }
            if (startsWith("x--")) {
                position += 3
                return AssociationOperator(
                    AssociationOperatorKind.BackwardCross,
                    2,
                    start,
                    position,
                )
            }
            if (startsWith("<--")) {
                position += 1
                val dashStart = position
                while (peek() == '-') position += 1
                return AssociationOperator(
                    AssociationOperatorKind.BackwardSolid,
                    position - dashStart,
                    start,
                    position,
                )
            }
            if (peek() != '-') return null
            val dashStart = position
            while (peek() == '-') position += 1
            val dashes = position - dashStart
            if (dashes < 2) {
                position = start
                return null
            }
            val kind = when (peek()) {
                '>' -> {
                    position += 1
                    AssociationOperatorKind.ForwardSolid
                }
                'o' -> {
                    if (dashes != 2) {
                        position = start
                        return null
                    }
                    position += 1
                    AssociationOperatorKind.ForwardCircle
                }
                'x' -> {
                    if (dashes != 2) {
                        position = start
                        return null
                    }
                    position += 1
                    AssociationOperatorKind.ForwardCross
                }
                else -> AssociationOperatorKind.Markerless
            }
            return AssociationOperator(kind, dashes, start, position)
        }

        fun findClosingAssociationOperator(): AssociationOperator? {
            var index = position
            var quote: Char? = null
            var markdown = false
            while (index < source.length) {
                val current = source[index]
                val next = source.getOrNull(index + 1)
                if (markdown) {
                    if (current == '`' && next == '"') {
                        markdown = false
                        index += 2
                    } else {
                        index += 1
                    }
                    continue
                }
                if (quote != null) {
                    if (current == quote) quote = null
                    index += 1
                    continue
                }
                if (current == '"' && next == '`') {
                    markdown = true
                    index += 2
                    continue
                }
                if (current == '"' || current == '\'') {
                    quote = current
                    index += 1
                    continue
                }
                if (current == '-' && next == '-') {
                    val start = index
                    while (source.getOrNull(index) == '-') index += 1
                    val dashes = index - start
                    val kind = when (source.getOrNull(index)) {
                        '>' -> AssociationOperatorKind.ForwardSolid
                        'o' -> AssociationOperatorKind.ForwardCircle
                        'x' -> AssociationOperatorKind.ForwardCross
                        else -> AssociationOperatorKind.Markerless
                    }
                    val end = if (kind == AssociationOperatorKind.Markerless) index else index + 1
                    return AssociationOperator(kind, dashes, start, end)
                }
                index += 1
            }
            return null
        }

        fun location(at: Int = position): DraftLocation {
            var line = statement.line
            var column = 1
            var index = 0
            while (index < at.coerceAtMost(source.length)) {
                if (source[index] == '\n') {
                    line += 1
                    column = 1
                } else {
                    column += 1
                }
                index += 1
            }
            return DraftLocation(
                line = line,
                column = column,
                offset = statement.offset + at,
            )
        }

        private fun parseMetadataProperties(
            source: String,
            location: DraftLocation,
        ): GMResult<DraftMetadata, MermaidError> {
            val rawProperties = splitMetadataProperties(source)
            val properties = mutableListOf<DraftMetadataProperty>()
            for (raw in rawProperties) {
                val separator = raw.indexOf(':')
                if (separator <= 0 || separator == raw.lastIndex) {
                    return parseError(
                        location.line,
                        location.column,
                        "Invalid metadata property '$raw'",
                    )
                }
                val rawKey = raw.substring(0, separator).trim()
                val rawValue = raw.substring(separator + 1).trim()
                val key = decodePlain(rawKey)
                    ?: return parseError(
                        location.line,
                        location.column,
                        "Invalid metadata key '$rawKey'",
                    )
                val value: Any = when (rawValue) {
                    "true" -> true
                    "false" -> false
                    else -> decodePlain(rawValue)
                        ?: rawValue.takeIf(IDENTIFIER::matches)
                        ?: return parseError(
                            location.line,
                            location.column,
                            "Invalid metadata value '$rawValue'",
                        )
                }
                properties += DraftMetadataProperty(key, value, location)
            }
            return GMResult.Ok(DraftMetadata(properties))
        }

        private fun splitMetadataProperties(source: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            source.forEach { character ->
                when {
                    quote != null -> {
                        current.append(character)
                        if (character == quote) quote = null
                    }
                    character == '"' || character == '\'' -> {
                        quote = character
                        current.append(character)
                    }
                    character == ',' || character == '\n' -> {
                        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
                        current.clear()
                    }
                    else -> current.append(character)
                }
            }
            current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
            return result
        }

        private fun decodePlain(source: String): String? {
            if (source.length >= 2) {
                val quote = source.first()
                if ((quote == '"' || quote == '\'') && source.last() == quote) {
                    return source.substring(1, source.length - 1)
                }
            }
            return source.takeIf(IDENTIFIER::matches)
        }
    }

    private companion object {
        const val HEADER = "usecase-beta"
        const val COMMENT_PREFIX = "%%"
        const val ACTOR_KEYWORD = "actor"
        const val SYSTEM_BOUNDARY_KEYWORD = "systemBoundary"
        const val END_KEYWORD = "end"
        const val DIRECTION_KEYWORD = "direction"
        const val NOTE_KEYWORD = "note"
        const val FOR_KEYWORD = "for"
        const val JSON_KEYWORD = "json"
        const val CLASS_DEF_KEYWORD = "classDef"
        const val CLASS_KEYWORD = "class"
        const val STYLE_KEYWORD = "style"

        val IDENTIFIER = Regex("""[A-Za-z0-9_]+""")
        val DIRECTION = Regex("""direction\s+(TD|TB|BT|LR|RL)""")
        // DOT_MATCHES_ALL is not available to common metadata; [\s\S] preserves multiline matching.
        val ACCESSIBILITY_TITLE = Regex("""accTitle[\t ]*:([\s\S]*)""")
        val ACCESSIBILITY_DESCRIPTION =
            Regex("""accDescr[\t ]*:([\s\S]*)""")
        val CLASS_DEF = Regex(
            """classDef\s+([A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)*)\s+([\s\S]+)""",
        )
        val CLASS_ASSIGNMENT = Regex(
            """class\s+([A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)*)\s+""" +
                """([A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)*)""",
        )
        val STYLE_ASSIGNMENT = Regex(
            """style\s+([A-Za-z0-9_]+)\s+([\s\S]+)""",
        )
        val FORBIDDEN_PLANT_UML = setOf(
            "allowmixing",
            "newpage",
            "package",
            "rectangle",
            "skinparam",
        )

        fun Char.isAsciiWord(): Boolean =
            this == '_' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }
}
