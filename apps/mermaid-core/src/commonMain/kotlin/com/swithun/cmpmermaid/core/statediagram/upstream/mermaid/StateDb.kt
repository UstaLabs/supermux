package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidUrlSanitizer

/**
 * Kotlin translation of Mermaid 12.0.0 stateDb.ts.
 *
 * The Jison parser first creates the statement tree. [setRootDocument] then
 * applies Mermaid's v2 document translation before extracting the state,
 * relation, style, and interaction indexes consumed by the unified renderer.
 */
internal class StateDb(
    diagramTitle: String? = null,
) {
    private var rootDocument = mutableListOf<StateStatement>()
    private val states = linkedMapOf<String, StateNodeStatement>()
    private val relations = mutableListOf<StateDiagramEdge>()
    private val classes = linkedMapOf<String, StateStyleClass>()
    private val links = linkedMapOf<String, StateLink>()
    private var startEndCount = 0
    private var dividerCount = 0
    private var generatedIdCount = 0

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun setRootDocument(document: MutableList<StateStatement>) {
        rootDocument = document
        translateDocument(
            parentId = ROOT_ID,
            document = rootDocument,
        )
        extract()
    }

    fun getRootDocument(): List<StateStatement> = rootDocument

    fun getStates(): Map<String, StateNodeStatement> = states

    fun getRelations(): List<StateDiagramEdge> = relations

    fun getClasses(): Map<String, StateStyleClass> = classes

    fun getLinks(): Map<String, StateLink> = links

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }

    fun getDividerId(): String {
        dividerCount += 1
        return "divider-id-$dividerCount"
    }

    fun setDirection(direction: String) {
        val existing = rootDocument.filterIsInstance<StateDirectionStatement>().firstOrNull()
        if (existing != null) {
            existing.value = direction
        } else {
            rootDocument.add(0, StateDirectionStatement(direction))
        }
    }

    fun direction(
        document: List<StateStatement> = rootDocument,
        default: String = DEFAULT_DIRECTION,
    ): String = document
        .filterIsInstance<StateDirectionStatement>()
        .lastOrNull()
        ?.value
        ?: default

    fun trimColon(source: String): String =
        if (source.startsWith(':')) source.drop(1).trim() else source.trim()

    private fun extract() {
        states.clear()
        relations.clear()
        classes.clear()
        links.clear()
        startEndCount = 0

        rootDocument.forEach { statement ->
            when (statement) {
                is StateNodeStatement -> addState(statement)
                is StateRelationStatement -> addRelation(statement)
                is StateClassDefStatement -> addStyleClass(
                    statement.id.trim(),
                    statement.classes,
                )
                is StateStyleStatement -> handleStyle(statement)
                is StateApplyClassStatement ->
                    setCssClass(statement.id.trim(), statement.styleClass)
                is StateClickStatement -> addLink(statement)
                is StateDirectionStatement,
                is StateIgnoredStatement,
                -> Unit
            }
        }
    }

    private fun translateDocument(
        parentId: String,
        document: MutableList<StateStatement>,
    ) {
        document.forEach { statement ->
            when (statement) {
                is StateRelationStatement -> {
                    translateEndpoint(parentId, statement.state1, first = true)
                    translateEndpoint(parentId, statement.state2, first = false)
                }
                is StateNodeStatement -> translateEndpoint(parentId, statement, first = true)
                else -> Unit
            }
        }
    }

    private fun translateEndpoint(
        parentId: String,
        node: StateNodeStatement,
        first: Boolean,
    ) {
        if (node.id == START_END_ID) {
            node.id = "${parentId}_${if (first) "start" else "end"}"
            node.start = first
        } else {
            node.id = node.id.trim()
        }
        val nested = node.document ?: return
        val translated = mutableListOf<StateStatement>()
        var current = mutableListOf<StateStatement>()
        nested.forEach { statement ->
            if (statement is StateNodeStatement && statement.type == StateNodeType.Divider) {
                translated += statement.deepCopy(document = current.deepCopy())
                current = mutableListOf()
            } else {
                current += statement
            }
        }
        if (translated.isNotEmpty() && current.isNotEmpty()) {
            generatedIdCount += 1
            translated += StateNodeStatement(
                id = "divider-generated-$generatedIdCount",
                type = StateNodeType.Divider,
                document = current.deepCopy(),
            )
            node.document = translated
        }
        node.document?.forEach { statement ->
            when (statement) {
                is StateRelationStatement -> {
                    translateEndpoint(node.id, statement.state1, first = true)
                    translateEndpoint(node.id, statement.state2, first = false)
                }
                is StateNodeStatement -> translateEndpoint(node.id, statement, first = true)
                else -> Unit
            }
        }
    }

    private fun addState(statement: StateNodeStatement) {
        val id = statement.id.trim()
        val current = states.getOrPut(id) {
            StateNodeStatement(
                id = id,
                type = statement.type,
                document = statement.document,
            )
        }
        if (current.document == null) {
            current.document = statement.document
        }
        if (current.type == StateNodeType.Default) {
            current.type = statement.type
        }
        statement.descriptions
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { addDescription(id, it) }
        statement.note?.let { note ->
            current.note = note.copy(text = note.text)
        }
        statement.classes.forEach { setCssClass(id, it.trim()) }
        statement.styles.forEach { setStyle(id, it.trim()) }
        statement.textStyles.forEach { setTextStyle(id, it.trim()) }
    }

    private fun addRelation(statement: StateRelationStatement) {
        val first = statement.state1
        val second = statement.state2
        val id1 = startIdIfNeeded(first.id.trim())
        val type1 = startTypeIfNeeded(first.id.trim(), first.type)
        val id2 = endIdIfNeeded(second.id.trim())
        val type2 = endTypeIfNeeded(second.id.trim(), second.type)
        addState(first.deepCopy(id = id1, type = type1))
        addState(second.deepCopy(id = id2, type = type2))
        relations += StateDiagramEdge(
            id1 = id1,
            id2 = id2,
            relationTitle = statement.description?.takeIf(String::isNotEmpty),
        )
    }

    private fun startIdIfNeeded(id: String): String {
        if (id != START_END_ID) return id
        startEndCount += 1
        return "start$startEndCount"
    }

    private fun endIdIfNeeded(id: String): String {
        if (id != START_END_ID) return id
        startEndCount += 1
        return "end$startEndCount"
    }

    private fun startTypeIfNeeded(
        id: String,
        type: StateNodeType,
    ): StateNodeType = if (id == START_END_ID) StateNodeType.Start else type

    private fun endTypeIfNeeded(
        id: String,
        type: StateNodeType,
    ): StateNodeType = if (id == START_END_ID) StateNodeType.End else type

    private fun addDescription(
        id: String,
        description: String,
    ) {
        val normalized = if (description.startsWith(':')) {
            description.drop(1).trim()
        } else {
            description
        }
        states[id]?.descriptions?.add(normalized)
    }

    private fun addStyleClass(
        id: String,
        styleAttributes: String,
    ) {
        val styleClass = classes.getOrPut(id) { StateStyleClass(id) }
        styleAttributes.split(STYLE_SEPARATOR).forEach { attribute ->
            val fixed = attribute.replace(FIRST_SEMICOLON, "$1").trim()
            if (COLOR_KEYWORD.containsMatchIn(attribute)) {
                styleClass.textStyles += fixed
                    .replace(FILL_KEYWORD, "bgFill")
                    .replace(COLOR_KEYWORD, "fill")
            }
            if (fixed.isNotEmpty()) {
                styleClass.styles += fixed
            }
        }
    }

    private fun handleStyle(statement: StateStyleStatement) {
        val styles = statement.styleClass.split(',').map { style ->
            style.replace(";", "").trim()
        }
        statement.id.split(',').forEach { rawId ->
            val id = rawId.trim()
            ensureState(id)
            states[id]?.styles?.addAll(styles.filter(String::isNotEmpty))
        }
    }

    private fun setCssClass(
        ids: String,
        cssClassName: String,
    ) {
        ids.split(',').forEach { rawId ->
            val id = rawId.trim()
            ensureState(id)
            cssClassName.split(' ')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { cssClass ->
                    states[id]?.classes?.add(cssClass)
                }
        }
    }

    private fun setStyle(
        id: String,
        style: String,
    ) {
        states[id]?.styles?.add(style)
    }

    private fun setTextStyle(
        id: String,
        style: String,
    ) {
        states[id]?.textStyles?.add(style)
    }

    private fun ensureState(id: String) {
        if (id !in states) {
            addState(StateNodeStatement(id = id))
        }
    }

    private fun addLink(statement: StateClickStatement) {
        val id = statement.state.id.trim()
        ensureState(id)
        links[id] = StateLink(
            url = MermaidUrlSanitizer.sanitize(statement.url.unquote()),
            tooltip = MermaidPreprocessor.decodeEntities(statement.tooltip.unquote()),
        )
    }

    private fun MutableList<StateStatement>.deepCopy(): MutableList<StateStatement> =
        mapTo(mutableListOf()) { it.deepCopy() }

    private fun StateStatement.deepCopy(): StateStatement = when (this) {
        is StateNodeStatement -> deepCopy()
        is StateRelationStatement -> copy(
            state1 = state1.deepCopy(),
            state2 = state2.deepCopy(),
        )
        is StateClassDefStatement -> copy()
        is StateStyleStatement -> copy()
        is StateApplyClassStatement -> copy()
        is StateDirectionStatement -> copy()
        is StateClickStatement -> copy(state = state.deepCopy())
        is StateIgnoredStatement -> copy()
    }

    private fun StateNodeStatement.deepCopy(
        id: String = this.id,
        type: StateNodeType = this.type,
        document: MutableList<StateStatement>? = this.document?.deepCopy(),
    ): StateNodeStatement = copy(
        id = id,
        type = type,
        descriptions = descriptions.toMutableList(),
        document = document,
        note = note?.copy(),
        classes = classes.toMutableList(),
        styles = styles.toMutableList(),
        textStyles = textStyles.toMutableList(),
    )

    private fun String.unquote(): String =
        removePrefix("\"").removeSuffix("\"")

    private companion object {
        const val ROOT_ID = "root"
        const val START_END_ID = "[*]"
        const val DEFAULT_DIRECTION = "TB"
        const val STYLE_SEPARATOR = ","
        val FIRST_SEMICOLON = Regex("([^;]*);")
        val COLOR_KEYWORD = Regex("color")
        val FILL_KEYWORD = Regex("fill")
    }
}
