package com.swithun.cmpmermaid.core.c4.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidC4Options
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/c4/c4Db.ts.
 */
internal class C4Db(
    val config: MermaidC4Options,
    frontmatterTitle: String?,
) {
    private val shapes = linkedMapOf<String, C4Shape>()
    private val boundaries = linkedMapOf(
        GLOBAL_BOUNDARY to C4Boundary(
            alias = GLOBAL_BOUNDARY,
            label = C4Text(GLOBAL_BOUNDARY),
            type = C4Text(GLOBAL_BOUNDARY),
            parentBoundary = "",
            wrap = false,
        ),
    )
    private val relations = mutableListOf<C4Relation>()
    private val boundaryParseStack = mutableListOf("")
    private var currentBoundaryParse = GLOBAL_BOUNDARY
    private var parentBoundaryParse = ""

    var diagramType: C4DiagramType? = null
        private set
    var diagramTitle: String? = frontmatterTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set
    var c4ShapeInRow: Int = DEFAULT_SHAPES_IN_ROW
        private set
    var c4BoundaryInRow: Int = DEFAULT_BOUNDARIES_IN_ROW
        private set

    fun setC4Type(value: C4DiagramType) {
        diagramType = value
    }

    fun setTitle(value: String) {
        diagramTitle = decode(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = decode(value)
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = decode(value)
    }

    fun addPersonOrSystem(
        type: C4ElementType,
        attributes: List<C4Attribute>,
    ) {
        val alias = attributes.positional(0) ?: return
        val label = attributes.positional(1).orEmpty()
        val shape = shapes[alias] ?: C4Shape(
            alias = alias,
            label = C4Text(label),
            typeC4Shape = type,
            parentBoundary = currentBoundaryParse,
            wrap = config.wrap,
        ).also { shapes[alias] = it }
        shape.label = C4Text(label)
        shape.typeC4Shape = type
        shape.parentBoundary = currentBoundaryParse
        shape.wrap = config.wrap
        applyOptionalAttributes(
            target = shape,
            attributes = attributes.drop(2),
            positionalFields = listOf("descr", "sprite", "tags", "link"),
        )
    }

    fun addContainer(
        type: C4ElementType,
        attributes: List<C4Attribute>,
    ) {
        addTechnicalShape(type, attributes)
    }

    fun addComponent(
        type: C4ElementType,
        attributes: List<C4Attribute>,
    ) {
        addTechnicalShape(type, attributes)
    }

    private fun addTechnicalShape(
        type: C4ElementType,
        attributes: List<C4Attribute>,
    ) {
        val alias = attributes.positional(0) ?: return
        val label = attributes.positional(1).orEmpty()
        val shape = shapes[alias] ?: C4Shape(
            alias = alias,
            label = C4Text(label),
            typeC4Shape = type,
            parentBoundary = currentBoundaryParse,
            wrap = config.wrap,
        ).also { shapes[alias] = it }
        shape.label = C4Text(label)
        shape.typeC4Shape = type
        shape.parentBoundary = currentBoundaryParse
        shape.wrap = config.wrap
        applyOptionalAttributes(
            target = shape,
            attributes = attributes.drop(2),
            positionalFields = listOf("techn", "descr", "sprite", "tags", "link"),
        )
    }

    fun addPersonOrSystemBoundary(
        attributes: List<C4Attribute>,
    ) {
        addBoundary(
            attributes = attributes,
            defaultType = "system",
            nodeType = null,
            positionalFields = listOf("type", "tags", "link"),
        )
    }

    fun addContainerBoundary(
        attributes: List<C4Attribute>,
    ) {
        addBoundary(
            attributes = attributes,
            defaultType = "container",
            nodeType = null,
            positionalFields = listOf("type", "tags", "link"),
        )
    }

    fun addDeploymentNode(
        nodeType: String,
        attributes: List<C4Attribute>,
    ) {
        addBoundary(
            attributes = attributes,
            defaultType = "node",
            nodeType = nodeType,
            positionalFields = listOf("type", "descr", "sprite", "tags", "link"),
        )
    }

    private fun addBoundary(
        attributes: List<C4Attribute>,
        defaultType: String,
        nodeType: String?,
        positionalFields: List<String>,
    ) {
        val alias = attributes.positional(0) ?: return
        val label = attributes.positional(1).orEmpty()
        val boundary = boundaries[alias] ?: C4Boundary(
            alias = alias,
            label = C4Text(label),
            type = C4Text(defaultType),
            parentBoundary = currentBoundaryParse,
            wrap = config.wrap,
        ).also { boundaries[alias] = it }
        boundary.label = C4Text(label)
        boundary.type = C4Text(defaultType)
        boundary.parentBoundary = currentBoundaryParse
        boundary.wrap = config.wrap
        boundary.nodeType = nodeType
        applyOptionalAttributes(
            target = boundary,
            attributes = attributes.drop(2),
            positionalFields = positionalFields,
        )

        parentBoundaryParse = currentBoundaryParse
        currentBoundaryParse = alias
        boundaryParseStack += parentBoundaryParse
    }

    fun popBoundaryParseStack(): Boolean {
        if (boundaryParseStack.size <= 1) {
            return false
        }
        currentBoundaryParse = parentBoundaryParse
        boundaryParseStack.removeAt(boundaryParseStack.lastIndex)
        parentBoundaryParse = boundaryParseStack.lastOrNull().orEmpty()
        return true
    }

    fun addRel(
        type: String,
        attributes: List<C4Attribute>,
        ignoreIndex: Boolean,
    ) {
        val effective = if (ignoreIndex) attributes.drop(1) else attributes
        val from = effective.positional(0) ?: return
        val to = effective.positional(1) ?: return
        val label = effective.positional(2) ?: return
        val relation = relations.firstOrNull { rel -> rel.from == from && rel.to == to }
            ?: C4Relation(
                type = type,
                from = from,
                to = to,
                label = C4Text(label),
                wrap = config.wrap,
            ).also(relations::add)
        relation.type = type
        relation.label = C4Text(label)
        relation.wrap = config.wrap
        applyOptionalAttributes(
            target = relation,
            attributes = effective.drop(3),
            positionalFields = listOf("techn", "descr", "sprite", "tags", "link"),
        )
    }

    fun updateElStyle(attributes: List<C4Attribute>) {
        val alias = attributes.positional(0) ?: return
        val shape = shapes[alias]
        val boundary = boundaries[alias]
        if (shape == null && boundary == null) return
        val values = attributes.drop(1)
        val fields = listOf(
            "bgColor",
            "fontColor",
            "borderColor",
            "shadowing",
            "shape",
            "sprite",
            "techn",
            "legendText",
            "legendSprite",
        )
        values.forEachIndexed { index, attribute ->
            val (key, value) = attribute.keyValue(fields.getOrNull(index)) ?: return@forEachIndexed
            if (shape != null) {
                applyShapeField(shape, key, value)
            } else if (boundary != null) {
                applyBoundaryField(boundary, key, value)
            }
        }
    }

    fun updateRelStyle(attributes: List<C4Attribute>) {
        val from = attributes.positional(0) ?: return
        val to = attributes.positional(1) ?: return
        val relation = relations.firstOrNull { rel -> rel.from == from && rel.to == to } ?: return
        val fields = listOf("textColor", "lineColor", "offsetX", "offsetY")
        attributes.drop(2).forEachIndexed { index, attribute ->
            val (key, value) = attribute.keyValue(fields.getOrNull(index)) ?: return@forEachIndexed
            applyRelationField(relation, key, value)
        }
    }

    fun updateLayoutConfig(attributes: List<C4Attribute>) {
        val fields = listOf("c4ShapeInRow", "c4BoundaryInRow")
        attributes.forEachIndexed { index, attribute ->
            val (key, rawValue) = attribute.keyValue(fields.getOrNull(index))
                ?: return@forEachIndexed
            val value = rawValue.toIntOrNull() ?: return@forEachIndexed
            when (key) {
                "c4ShapeInRow" -> if (value >= 1) c4ShapeInRow = value
                "c4BoundaryInRow" -> if (value >= 1) c4BoundaryInRow = value
            }
        }
    }

    fun getC4ShapeArray(parentBoundary: String? = null): List<C4Shape> =
        shapes.values.filter { shape ->
            parentBoundary == null || shape.parentBoundary == parentBoundary
        }

    fun getC4Shape(alias: String): C4Shape? = shapes[alias]

    fun getElementBounds(alias: String): C4BoundaryOrShape? =
        shapes[alias]?.let(C4BoundaryOrShape::Shape)
            ?: boundaries[alias]?.let(C4BoundaryOrShape::Boundary)

    fun getBoundaries(parentBoundary: String? = null): List<C4Boundary> =
        boundaries.values.filter { boundary ->
            parentBoundary == null || boundary.parentBoundary == parentBoundary
        }

    fun getRelations(): List<C4Relation> = relations.toList()

    fun isAtGlobalBoundary(): Boolean =
        currentBoundaryParse == GLOBAL_BOUNDARY && boundaryParseStack.size == 1

    private fun applyOptionalAttributes(
        target: Any,
        attributes: List<C4Attribute>,
        positionalFields: List<String>,
    ) {
        attributes.forEachIndexed { index, attribute ->
            val (key, value) = attribute.keyValue(positionalFields.getOrNull(index))
                ?: return@forEachIndexed
            when (target) {
                is C4Shape -> applyShapeField(target, key, value)
                is C4Boundary -> applyBoundaryField(target, key, value)
                is C4Relation -> applyRelationField(target, key, value)
            }
        }
    }

    private fun applyShapeField(
        shape: C4Shape,
        key: String,
        value: String,
    ) {
        when (key) {
            "label" -> shape.label = C4Text(value)
            "descr" -> shape.descr = C4Text(value)
            "techn" -> shape.techn = C4Text(value)
            "sprite" -> shape.sprite = value
            "tags" -> shape.tags = value
            "link" -> shape.link = value
            "bgColor" -> shape.bgColor = value
            "fontColor" -> shape.fontColor = value
            "borderColor" -> shape.borderColor = value
            "shadowing" -> shape.shadowing = value
            "shape" -> shape.shape = value
            "legendText" -> shape.legendText = value
            "legendSprite" -> shape.legendSprite = value
            else -> shape.attributes[key] = value
        }
    }

    private fun applyBoundaryField(
        boundary: C4Boundary,
        key: String,
        value: String,
    ) {
        when (key) {
            "label" -> boundary.label = C4Text(value)
            "type" -> boundary.type = C4Text(value)
            "descr" -> boundary.descr = C4Text(value)
            "techn" -> boundary.techn = C4Text(value)
            "sprite" -> boundary.sprite = value
            "tags" -> boundary.tags = value
            "link" -> boundary.link = value
            "bgColor" -> boundary.bgColor = value
            "fontColor" -> boundary.fontColor = value
            "borderColor" -> boundary.borderColor = value
            "shadowing" -> boundary.shadowing = value
            "shape" -> boundary.shape = value
            "legendText" -> boundary.legendText = value
            "legendSprite" -> boundary.legendSprite = value
            else -> boundary.attributes[key] = value
        }
    }

    private fun applyRelationField(
        relation: C4Relation,
        key: String,
        value: String,
    ) {
        when (key) {
            "label" -> relation.label = C4Text(value)
            "descr" -> relation.descr = C4Text(value)
            "techn" -> relation.techn = C4Text(value)
            "sprite" -> relation.sprite = value
            "tags" -> relation.tags = value
            "link" -> relation.link = value
            "textColor" -> relation.textColor = value
            "lineColor" -> relation.lineColor = value
            "offsetX" -> value.toIntOrNull()?.let { relation.offsetX = it }
            "offsetY" -> value.toIntOrNull()?.let { relation.offsetY = it }
            else -> relation.attributes[key] = value
        }
    }

    private fun List<C4Attribute>.positional(index: Int): String? =
        (getOrNull(index) as? C4Attribute.Positional)?.value

    private fun C4Attribute.keyValue(defaultKey: String?): Pair<String, String>? = when (this) {
        is C4Attribute.Named -> key to value
        is C4Attribute.Positional -> defaultKey?.let { key -> key to value }
    }

    private fun decode(value: String): String = MermaidPreprocessor.decodeEntities(value)

    private companion object {
        const val GLOBAL_BOUNDARY = "global"
        const val DEFAULT_SHAPES_IN_ROW = 4
        const val DEFAULT_BOUNDARIES_IN_ROW = 2
    }
}

internal sealed interface C4BoundaryOrShape {
    val alias: String
    var bounds: com.swithun.cmpmermaid.core.SceneRect?

    data class Shape(
        val value: C4Shape,
    ) : C4BoundaryOrShape {
        override val alias: String
            get() = value.alias
        override var bounds: com.swithun.cmpmermaid.core.SceneRect?
            get() = value.bounds
            set(value) {
                this.value.bounds = value
            }
    }

    data class Boundary(
        val value: C4Boundary,
    ) : C4BoundaryOrShape {
        override val alias: String
            get() = value.alias
        override var bounds: com.swithun.cmpmermaid.core.SceneRect?
            get() = value.bounds
            set(value) {
                this.value.bounds = value
            }
    }
}
