package com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 erDb.ts.
 */
internal class ErDb(
    diagramTitle: String? = null,
) {
    private val entities = linkedMapOf<String, ErEntity>()
    private val relationships = mutableListOf<ErRelationship>()
    private val classes = linkedMapOf<String, ErStyleClass>()
    private val subGraphs = mutableListOf<ErSubGraph>()
    private val subGraphLookup = linkedMapOf<String, ErSubGraph>()
    private var subCount = 0
    private var direction = "TB"

    var subgraphDepth: Int = 0
    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun addEntity(
        name: String,
        alias: String = "",
    ): ErEntity {
        val existing = entities[name]
        if (existing != null) {
            if (existing.alias.isEmpty() && alias.isNotEmpty()) {
                existing.alias = alias
            }
            return existing
        }
        return ErEntity(
            sourceName = name,
            id = "entity-$name-${entities.size}",
            label = name,
            alias = alias,
        ).also { entities[name] = it }
    }

    fun addAttributes(
        entityName: String,
        attributes: List<ErAttribute>,
    ) {
        val entity = addEntity(entityName)
        attributes.asReversed().forEach(entity.attributes::add)
    }

    fun addRelationship(
        entityA: String,
        roleA: String,
        entityB: String,
        specification: ErRelationshipSpec,
    ) {
        val entityAId = subGraphLookup[entityA]?.id ?: addEntity(entityA).id
        val entityBId = subGraphLookup[entityB]?.id ?: addEntity(entityB).id
        relationships += ErRelationship(
            entityA = entityAId,
            roleA = roleA,
            entityB = entityBId,
            specification = specification,
        )
    }

    fun addCssStyles(
        ids: List<String>,
        styles: List<String>,
    ) {
        ids.forEach { id ->
            entities[id]?.styles?.addAll(styles)
            subGraphLookup[id]?.styles?.addAll(styles)
        }
    }

    fun addClass(
        ids: List<String>,
        styles: List<String>,
    ) {
        ids.forEach { id ->
            val styleClass = classes.getOrPut(id) { ErStyleClass(id) }
            styles.forEach { style ->
                if ("color" in style) {
                    styleClass.textStyles += style.replace("fill", "bgFill")
                }
                styleClass.styles += style
            }
        }
    }

    fun setClass(
        ids: List<String>,
        classNames: List<String>,
    ) {
        ids.forEach { id ->
            entities[id]?.classes?.addAll(classNames)
            subGraphLookup[id]?.classes?.addAll(classNames)
        }
    }

    fun addSubGraph(
        header: ErSubGraphHeader,
        document: List<ErDocumentEntry>,
    ): String {
        val id = header.id.trim().ifEmpty { "subGraph$subCount" }
        val seen = linkedSetOf<String>()
        var nestedDirection: String? = null
        val nodes = mutableListOf<String>()
        document.forEach { entry ->
            when (entry) {
                is ErDirectionStatement -> nestedDirection = entry.value
                is ErEntityReference -> if (seen.add(entry.id)) nodes += entry.id.trim()
                is ErSubGraphReference -> if (seen.add(entry.id)) nodes += entry.id.trim()
            }
        }
        val existingNodes = subGraphs.flatMapTo(linkedSetOf(), ErSubGraph::nodes)
        nodes.removeAll(existingNodes)
        val subGraph = ErSubGraph(
            id = id,
            nodes = nodes,
            title = MermaidPreprocessor.decodeEntities(header.text).trim(),
            direction = nestedDirection,
        )
        subCount += 1
        subGraphs += subGraph
        subGraphLookup[id] = subGraph
        return id
    }

    fun compiledStyles(classNames: List<String>): List<String> = buildList {
        classNames.forEach { className ->
            classes[className]?.let { styleClass ->
                addAll(styleClass.styles.map(String::trim))
                addAll(styleClass.textStyles.map(String::trim))
            }
        }
    }

    fun getEntities(): Map<String, ErEntity> = entities

    fun getRelationships(): List<ErRelationship> = relationships

    fun getClasses(): Map<String, ErStyleClass> = classes

    fun getSubGraphs(): List<ErSubGraph> = subGraphs

    fun getDirection(): String = direction

    fun setDirection(value: String) {
        direction = value
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }
}
