package com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSecurityLevel

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12
 * classDiagram.jison semantic actions.
 */
internal class ClassJisonParser(
    private val securityLevel: MermaidSecurityLevel,
    private val diagramTitle: String? = null,
) {
    fun parse(source: String): GMResult<ClassDb, MermaidError> {
        val db = ClassDb(
            securityLevel = securityLevel,
            diagramTitle = diagramTitle,
        )
        val lexer = ClassJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: ClassJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = ClassJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is ClassJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is ClassJisonCell.Reduce -> {
                    val production = ClassJisonTables.productions
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
                    val goto = ClassJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? ClassJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                ClassJisonCell.Accept -> return GMResult.Ok(db)
                is ClassJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: ClassDb,
        token: ClassJisonToken,
    ): GMResult<Any?, MermaidError> {
        return when (production) {
        8 -> stringValue(values, 1, token, production, "class label")
        9, 10, 13, 15 -> GMResult.Ok(values.firstOrNull())
        11, 14 -> concatenate(values, listOf(0, 2), ".", token, production)
        12, 16 -> concatenate(values, listOf(0, 1), "", token, production)
        17, 18 -> concatenate(values, listOf(0, 1), "~", token, production, suffix = "~")
        19 -> relation(values.firstOrNull(), token, production) { relation ->
            db.addRelation(relation)
        }
        20 -> relation(values.firstOrNull(), token, production) { relation ->
            relation.title = db.cleanupLabel(values.getOrNull(1) as? String ?: "")
            db.addRelation(relation)
        }
        31 -> stringValue(values, 1, token, production, "accessibility title").map { value ->
            value.trim().also(db::setAccessibilityTitle)
        }
        32, 33 -> stringValue(
            values,
            values.lastIndex,
            token,
            production,
            "accessibility description",
        ).map { value ->
            value.trim().also(db::setAccessibilityDescription)
        }
        34, 35 -> namespaceStatement(values, db, token, production)
        36 -> stringValue(values, 1, token, production, "namespace name").map(db::addNamespace)
        37 -> {
            val id = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "namespace name", 1)
            val label = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "namespace label", 2)
            GMResult.Ok(db.addNamespace(id, label))
        }
        38 -> stringValue(values, 0, token, production, "class name").map { name ->
            ClassNamespaceContents(
                classes = mutableListOf(name),
                notes = mutableListOf(),
            )
        }
        39 -> stringValue(values, 0, token, production, "class name").map { name ->
            ClassNamespaceContents(
                classes = mutableListOf(name),
                notes = mutableListOf(),
            )
        }
        40 -> namespaceContents(values.getOrNull(2), token, production).flatMap { contents ->
            stringValue(values, 0, token, production, "class name").map { name ->
                contents.copy(classes = contents.classes.toMutableList().apply { add(0, name) })
            }
        }
        41 -> stringValue(values, 0, token, production, "note id").map { note ->
            ClassNamespaceContents(
                classes = mutableListOf(),
                notes = mutableListOf(note),
            )
        }
        42 -> stringValue(values, 0, token, production, "note id").map { note ->
            ClassNamespaceContents(
                classes = mutableListOf(),
                notes = mutableListOf(note),
            )
        }
        43 -> namespaceContents(values.getOrNull(2), token, production).flatMap { contents ->
            stringValue(values, 0, token, production, "note id").map { note ->
                contents.copy(notes = contents.notes.toMutableList().apply { add(0, note) })
            }
        }
        44, 45 -> GMResult.Ok(ClassNamespaceContents())
        46 -> GMResult.Ok(values.lastOrNull())
        48 -> twoStrings(values, 0, 2, token, production, "class id", "CSS class") {
            id, cssClass -> db.setCssClass(id, cssClass)
        }
        49 -> addMembers(values, classIndex = 0, membersIndex = 2, db, token, production)
        51 -> {
            when (
                val css = twoStrings(
                    values,
                    0,
                    2,
                    token,
                    production,
                    "class id",
                    "CSS class",
                ) { id, className -> db.setCssClass(id, className) }
            ) {
                is GMResult.Err -> css
                is GMResult.Ok -> addMembers(
                    values,
                    classIndex = 0,
                    membersIndex = 4,
                    db,
                    token,
                    production,
                )
            }
        }
        52 -> addAnnotation(values, 0, 2, db, token, production)
        53 -> when (
            val annotation = addAnnotation(values, 0, 2, db, token, production)
        ) {
            is GMResult.Err -> annotation
            is GMResult.Ok -> addMembers(
                values,
                classIndex = 0,
                membersIndex = 5,
                db,
                token,
                production,
            )
        }
        54 -> addAnnotation(values, 0, 2, db, token, production)
        55 -> stringValue(values, 1, token, production, "class name").map { name ->
            db.addClass(name)
        }
        56 -> {
            val name = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "class name", 1)
            val label = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "class label", 2)
            db.addClass(name)
            db.setClassLabel(name, label)
            GMResult.Ok(name)
        }
        60 -> addAnnotation(values, 3, 1, db, token, production)
        61 -> stringValue(values, 0, token, production, "class member").map {
            mutableListOf(it)
        }
        62 -> {
            val member = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "class member", 0)
            val members = stringList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "class members", 1)
            members += member
            GMResult.Ok(members)
        }
        64 -> twoStrings(values, 0, 1, token, production, "class id", "member") {
            id, member -> db.addMember(id, db.cleanupLabel(member))
        }
        67 -> classRelation(values, 0, 2, 1, null, null, token, production)
        68 -> classRelation(values, 0, 3, 2, 1, null, token, production)
        69 -> classRelation(values, 0, 3, 1, null, 2, token, production)
        70 -> classRelation(values, 0, 4, 2, 1, 3, token, production)
        71 -> {
            val className = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "note class", 1)
            val text = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "note text", 2)
            GMResult.Ok(db.addNote(text, className))
        }
        72 -> stringValue(values, 1, token, production, "note text").map(db::addNote)
        73 -> {
            val ids = stringList(values.getOrNull(1), token, production, 1)
                ?: return semanticError(token, production, "class definition ids", 1)
            val styles = stringList(values.getOrNull(2), token, production, 2)
                ?: return semanticError(token, production, "class definition styles", 2)
            db.defineClass(ids, styles)
            GMResult.Ok(values.firstOrNull())
        }
        74 -> stringValue(values, 0, token, production, "class id").map {
            mutableListOf(it)
        }
        75 -> {
            val ids = stringList(values.getOrNull(0), token, production, 0)
                ?: return semanticError(token, production, "class ids", 0)
            val id = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "class id", 2)
            ids += id
            GMResult.Ok(ids)
        }
        76, 77, 78, 79 -> {
            db.setDirection(
                when (production) {
                    76 -> "TB"
                    77 -> "BT"
                    78 -> "RL"
                    else -> "LR"
                },
            )
            GMResult.Ok(values.firstOrNull())
        }
        80 -> relationDefinition(values, 0, 2, 1, token, production)
        81 -> relationDefinition(
            values,
            null,
            1,
            0,
            token,
            production,
        )
        82 -> relationDefinition(
            values,
            0,
            null,
            1,
            token,
            production,
        )
        83 -> relationDefinition(
            values,
            null,
            null,
            0,
            token,
            production,
        )
        84 -> GMResult.Ok(ClassRelationType.AGGREGATION)
        85 -> GMResult.Ok(ClassRelationType.EXTENSION)
        86 -> GMResult.Ok(ClassRelationType.COMPOSITION)
        87 -> GMResult.Ok(ClassRelationType.DEPENDENCY)
        88 -> GMResult.Ok(ClassRelationType.LOLLIPOP)
        89 -> GMResult.Ok(ClassLineType.LINE)
        90 -> GMResult.Ok(ClassLineType.DOTTED_LINE)
        in 91..104 -> clickStatement(production, values, db, token)
        105 -> {
            val id = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "class id", 1)
            val styles = stringList(values.getOrNull(2), token, production, 2)
                ?: return semanticError(token, production, "class styles", 2)
            db.setCssStyle(id, styles)
            GMResult.Ok(values.firstOrNull())
        }
        106 -> twoStrings(values, 1, 2, token, production, "class ids", "CSS class") {
            ids, cssClass -> db.setCssClass(ids, cssClass)
        }
        107 -> stringValue(values, 0, token, production, "style").map {
            mutableListOf(it)
        }
        108 -> {
            val styles = stringList(values.getOrNull(0), token, production, 0)
                ?: return semanticError(token, production, "styles", 0)
            val style = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "style", 2)
            styles += style
            GMResult.Ok(styles)
        }
        110 -> concatenate(values, listOf(0, 1), "", token, production)
            else -> GMResult.Ok(values.firstOrNull())
        }
    }

    private fun namespaceStatement(
        values: List<Any?>,
        db: ClassDb,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val id = values.firstOrNull() as? String
            ?: return semanticError(token, production, "namespace id", 0)
        val contentsIndex = if (production == 34) 2 else 3
        val contents = values.getOrNull(contentsIndex) as? ClassNamespaceContents
            ?: return semanticError(token, production, "namespace contents", contentsIndex)
        db.addClassesToNamespace(id, contents.classes, contents.notes)
        db.popNamespace()
        return GMResult.Ok(id)
    }

    private fun addMembers(
        values: List<Any?>,
        classIndex: Int,
        membersIndex: Int,
        db: ClassDb,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val id = values.getOrNull(classIndex) as? String
            ?: return semanticError(token, production, "class id", classIndex)
        val members = stringList(values.getOrNull(membersIndex), token, production, membersIndex)
            ?: return semanticError(token, production, "class members", membersIndex)
        db.addMembers(id, members)
        return GMResult.Ok(id)
    }

    private fun addAnnotation(
        values: List<Any?>,
        classIndex: Int,
        annotationIndex: Int,
        db: ClassDb,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> =
        twoStrings(
            values,
            classIndex,
            annotationIndex,
            token,
            production,
            "class id",
            "annotation",
        ) { id, annotation -> db.addAnnotation(id, annotation) }

    private fun classRelation(
        values: List<Any?>,
        fromIndex: Int,
        toIndex: Int,
        relationIndex: Int,
        startTitleIndex: Int?,
        endTitleIndex: Int?,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val from = values.getOrNull(fromIndex) as? String
            ?: return semanticError(token, production, "relation source", fromIndex)
        val to = values.getOrNull(toIndex) as? String
            ?: return semanticError(token, production, "relation destination", toIndex)
        val relation = values.getOrNull(relationIndex) as? ClassRelationDefinition
            ?: return semanticError(token, production, "relation definition", relationIndex)
        return GMResult.Ok(
            ClassRelation(
                id1 = from,
                id2 = to,
                relation = relation,
                relationTitle1 = startTitleIndex
                    ?.let(values::getOrNull) as? String ?: "none",
                relationTitle2 = endTitleIndex
                    ?.let(values::getOrNull) as? String ?: "none",
            ),
        )
    }

    private fun relationDefinition(
        values: List<Any?>,
        type1Index: Int?,
        type2Index: Int?,
        lineIndex: Int,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val lineType = values.getOrNull(lineIndex) as? Int
            ?: return semanticError(token, production, "relation line type", lineIndex)
        val type1 = if (type1Index == null) {
            ClassRelationType.NONE
        } else {
            values.getOrNull(type1Index) as? Int
                ?: return semanticError(token, production, "relation start type", type1Index)
        }
        val type2 = if (type2Index == null) {
            ClassRelationType.NONE
        } else {
            values.getOrNull(type2Index) as? Int
                ?: return semanticError(token, production, "relation end type", type2Index)
        }
        return GMResult.Ok(ClassRelationDefinition(type1, type2, lineType))
    }

    private fun clickStatement(
        production: Int,
        values: List<Any?>,
        db: ClassDb,
        token: ClassJisonToken,
    ): GMResult<Any?, MermaidError> {
        val idIndex = 1
        val id = values.getOrNull(idIndex) as? String
            ?: return semanticError(token, production, "class id", idIndex)
        when (production) {
            91, 97 -> db.setClickEvent(
                ids = id,
                functionName = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "callback", 2),
            )
            92, 98 -> {
                val functionName = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "callback", 2)
                val tooltip = values.getOrNull(3) as? String
                    ?: return semanticError(token, production, "tooltip", 3)
                db.setClickEvent(id, functionName)
                db.setTooltip(id, tooltip)
            }
            93, 101 -> db.setLink(
                ids = id,
                link = values.getOrNull(if (production == 93) 2 else 3) as? String
                    ?: return semanticError(token, production, "link", 2),
            )
            94, 102 -> {
                val linkIndex = if (production == 94) 2 else 3
                val targetIndex = if (production == 94) 3 else 4
                db.setLink(
                    ids = id,
                    link = values.getOrNull(linkIndex) as? String
                        ?: return semanticError(token, production, "link", linkIndex),
                    target = values.getOrNull(targetIndex) as? String
                        ?: return semanticError(token, production, "link target", targetIndex),
                )
            }
            95, 103 -> {
                val linkIndex = if (production == 95) 2 else 3
                val tooltipIndex = if (production == 95) 3 else 4
                db.setLink(
                    ids = id,
                    link = values.getOrNull(linkIndex) as? String
                        ?: return semanticError(token, production, "link", linkIndex),
                )
                db.setTooltip(
                    id,
                    values.getOrNull(tooltipIndex) as? String
                        ?: return semanticError(
                            token,
                            production,
                            "tooltip",
                            tooltipIndex,
                        ),
                )
            }
            96, 104 -> {
                val linkIndex = if (production == 96) 2 else 3
                val tooltipIndex = if (production == 96) 3 else 4
                val targetIndex = if (production == 96) 4 else 5
                db.setLink(
                    ids = id,
                    link = values.getOrNull(linkIndex) as? String
                        ?: return semanticError(token, production, "link", linkIndex),
                    target = values.getOrNull(targetIndex) as? String
                        ?: return semanticError(token, production, "link target", targetIndex),
                )
                db.setTooltip(
                    id,
                    values.getOrNull(tooltipIndex) as? String
                        ?: return semanticError(
                            token,
                            production,
                            "tooltip",
                            tooltipIndex,
                        ),
                )
            }
            99, 100 -> {
                val functionName = values.getOrNull(2) as? String
                    ?: return semanticError(token, production, "callback", 2)
                val arguments = values.getOrNull(3) as? String
                    ?: return semanticError(token, production, "callback arguments", 3)
                db.setClickEvent(id, functionName, arguments)
                if (production == 100) {
                    db.setTooltip(
                        id,
                        values.getOrNull(4) as? String
                            ?: return semanticError(token, production, "tooltip", 4),
                    )
                }
            }
        }
        return GMResult.Ok(values.firstOrNull())
    }

    private fun relation(
        value: Any?,
        token: ClassJisonToken,
        production: Int,
        apply: (ClassRelation) -> Unit,
    ): GMResult<Any?, MermaidError> {
        val relation = value as? ClassRelation
            ?: return semanticError(token, production, "class relation", 0)
        apply(relation)
        return GMResult.Ok(relation)
    }

    private fun twoStrings(
        values: List<Any?>,
        firstIndex: Int,
        secondIndex: Int,
        token: ClassJisonToken,
        production: Int,
        firstName: String,
        secondName: String,
        apply: (String, String) -> Unit,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(firstIndex) as? String
            ?: return semanticError(token, production, firstName, firstIndex)
        val second = values.getOrNull(secondIndex) as? String
            ?: return semanticError(token, production, secondName, secondIndex)
        apply(first, second)
        return GMResult.Ok(first)
    }

    private fun concatenate(
        values: List<Any?>,
        indexes: List<Int>,
        separator: String,
        token: ClassJisonToken,
        production: Int,
        suffix: String = "",
    ): GMResult<Any?, MermaidError> {
        val parts = mutableListOf<String>()
        indexes.forEach { index ->
            val value = values.getOrNull(index) as? String
                ?: return semanticError(token, production, "text", index)
            parts += value
        }
        return GMResult.Ok(parts.joinToString(separator) + suffix)
    }

    private fun stringValue(
        values: List<Any?>,
        index: Int,
        token: ClassJisonToken,
        production: Int,
        name: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return semanticError(token, production, name, index)
        return GMResult.Ok(value)
    }

    private fun namespaceContents(
        value: Any?,
        token: ClassJisonToken,
        production: Int,
    ): GMResult<ClassNamespaceContents, MermaidError> {
        val contents = value as? ClassNamespaceContents
            ?: return semanticError(token, production, "namespace contents", 2)
        return GMResult.Ok(contents)
    }

    private fun stringList(
        value: Any?,
        token: ClassJisonToken,
        production: Int,
        index: Int,
    ): MutableList<String>? {
        val source = value as? List<*> ?: return null
        return source
            .mapNotNull { item -> item as? String }
            .takeIf { values -> values.size == source.size }
            ?.toMutableList()
    }

    private fun expectedMessage(
        state: Int,
        token: ClassJisonToken,
    ): String {
        val expected = ClassJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(ClassJisonTables.terminalNames::get)
            .filterNot { it == "error" }
            .joinToString()
        return "Unexpected '${token.name}'${if (expected.isEmpty()) "" else "; expected $expected"}"
    }

    private fun <T> GMResult<T, MermaidError>.map(
        transform: (T) -> Any?,
    ): GMResult<Any?, MermaidError> = when (this) {
        is GMResult.Ok -> GMResult.Ok(transform(value))
        is GMResult.Err -> this
    }

    private fun <T> GMResult<T, MermaidError>.flatMap(
        transform: (T) -> GMResult<Any?, MermaidError>,
    ): GMResult<Any?, MermaidError> = when (this) {
        is GMResult.Ok -> transform(value)
        is GMResult.Err -> this
    }

    private fun <T> semanticError(
        token: ClassJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> =
        parseError(token, "Production $production expected $expected at value $index")

    private fun <T> parseError(
        token: ClassJisonToken,
        message: String,
    ): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(token.line, token.column + 1, message))
}

private data class ClassNamespaceContents(
    val classes: MutableList<String> = mutableListOf(),
    val notes: MutableList<String> = mutableListOf(),
)
