package com.swithun.cmpmermaid.core.block.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/block/blockDB.ts.
 */
internal class BlockDb(
    val config: MermaidRenderOptions,
    diagramTitle: String? = null,
) {
    private var blockDatabase = linkedMapOf<String, Block>()
    private var edgeList = mutableListOf<Block>()
    private var edgeCount = linkedMapOf<String, Int>()
    private var classes = linkedMapOf<String, BlockClassDefinition>()
    private var rootBlock = root()
    private var blocks = mutableListOf<Block>()
    private var nextColorIndex = 0
    private var generatedId = 0

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    init {
        blockDatabase[rootBlock.id] = rootBlock
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value.trim()
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value.trim()
    }

    fun generateId(prefix: String = "block"): String {
        generatedId += 1
        return "$prefix-generated-$generatedId"
    }

    fun setHierarchy(input: List<Block>): GMResult<Unit, MermaidError> {
        rootBlock.children = input.toMutableList()
        when (val populated = populateBlockDatabase(input, rootBlock)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return populated
        }
        blocks = rootBlock.children
        return GMResult.Ok(Unit)
    }

    private fun populateBlockDatabase(
        blockList: List<Block>,
        parent: Block,
    ): GMResult<Unit, MermaidError> {
        val children = mutableListOf<Block>()
        blockList.forEach { block ->
            if (!block.label.isNullOrEmpty()) {
                when (val sanitized = MermaidTextPort.sanitizeText(block.label.orEmpty())) {
                    is GMResult.Ok -> block.label = sanitized.value
                    is GMResult.Err -> return sanitized
                }
            }
            when (block.type) {
                BlockType.ClassDefinition -> {
                    addStyleClass(block.id, block.css.orEmpty())
                    return@forEach
                }
                BlockType.ApplyClass -> {
                    setCssClass(block.id, block.styleClass.orEmpty())
                    return@forEach
                }
                BlockType.ApplyStyles -> {
                    val styles = block.stylesStr
                    if (styles != null) {
                        when (val applied = addStyleToNode(block.id, styles)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return applied
                        }
                    }
                    return@forEach
                }
                BlockType.ColumnSetting -> {
                    parent.columns = block.columns ?: -1
                    return@forEach
                }
                BlockType.Edge -> {
                    if (edgeList.size >= config.maxEdges) {
                        return GMResult.Err(
                            MermaidError.ResourceLimit(
                                resource = "Block edges",
                                actual = edgeList.size + 1,
                                maximum = config.maxEdges,
                            ),
                        )
                    }
                    val count = (edgeCount[block.id] ?: 0) + 1
                    edgeCount[block.id] = count
                    block.id = "$count-${block.id}"
                    edgeList += block
                    return@forEach
                }
                else -> Unit
            }

            if (block.label.isNullOrEmpty()) {
                block.label = if (block.type == BlockType.Composite) "" else block.id
            }
            val existing = blockDatabase[block.id]
            if (existing == null) {
                if (block.type == BlockType.Composite) {
                    block.colorIndex = nextColorIndex
                    nextColorIndex += 1
                }
                blockDatabase[block.id] = block
            } else {
                if (block.type != BlockType.Na) {
                    existing.type = block.type
                }
                if (block.label != block.id) {
                    existing.label = block.label
                }
            }

            if (block.children.isNotEmpty()) {
                when (val populated = populateBlockDatabase(block.children, block)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return populated
                }
            }
            if (block.type == BlockType.Space) {
                repeat(block.width ?: 1) { index ->
                    val copy = block.copy(
                        id = "${block.id}-$index",
                        children = block.children.map { child -> child.deepCopy() }.toMutableList(),
                        classes = block.classes.toMutableList(),
                        styles = block.styles.toMutableList(),
                    )
                    blockDatabase[copy.id] = copy
                    children += copy
                }
            } else if (existing == null) {
                children += block
            }
        }
        parent.children = children
        return GMResult.Ok(Unit)
    }

    fun addStyleClass(
        id: String,
        styleAttributes: String,
    ) {
        val definition = classes.getOrPut(id) { BlockClassDefinition(id) }
        styleAttributes.split(',').forEach { attribute ->
            val fixed = attribute.replace(TRAILING_SEMICOLON, "$1").trim()
            if ("color" in attribute) {
                definition.textStyles += fixed
                    .replace("fill", "bgFill")
                    .replace("color", "fill")
            }
            definition.styles += fixed
        }
    }

    private fun addStyleToNode(
        id: String,
        styles: String,
    ): GMResult<Unit, MermaidError> {
        val block = blockDatabase[id]
            ?: return GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Cannot apply Block style to unknown block '$id'",
                ),
            )
        block.styles = styles.split(',').toMutableList()
        return GMResult.Ok(Unit)
    }

    fun setCssClass(
        itemIds: String,
        cssClassName: String,
    ) {
        itemIds.split(',').forEach { rawId ->
            val block = blockDatabase[rawId] ?: rawId.trim().let { id ->
                Block(id = id, type = BlockType.Na).also { created ->
                    blockDatabase[id] = created
                }
            }
            block.classes += cssClassName
        }
    }

    fun getColumns(blockId: String): Int {
        val block = blockDatabase[blockId] ?: return -1
        return block.columns?.takeIf { it != 0 } ?: block.children.size.takeIf { it > 0 } ?: -1
    }

    fun getBlocksFlat(): List<Block> = blockDatabase.values.toList()

    fun getBlocks(): List<Block> = blocks

    fun getEdges(): List<Block> = edgeList

    fun getBlock(id: String): Block? = blockDatabase[id]

    fun setBlock(block: Block) {
        blockDatabase[block.id] = block
    }

    fun getClasses(): Map<String, BlockClassDefinition> = classes

    private fun root(): Block =
        Block(
            id = ROOT_ID,
            type = BlockType.Composite,
            columns = -1,
        )

    private fun Block.deepCopy(): Block = copy(
        children = children.map { child -> child.deepCopy() }.toMutableList(),
        classes = classes.toMutableList(),
        styles = styles.toMutableList(),
    )

    companion object {
        const val ROOT_ID = "root"
        private val TRAILING_SEMICOLON = Regex("""([^;]*);""")

        fun typeStringToType(typeString: String?): BlockType = when (typeString) {
            "[]" -> BlockType.Square
            "()" -> BlockType.Round
            "(())" -> BlockType.Circle
            ">]" -> BlockType.RectangleLeftInverseArrow
            "{}" -> BlockType.Diamond
            "{{}}" -> BlockType.Hexagon
            "([])" -> BlockType.Stadium
            "[[]]" -> BlockType.Subroutine
            "[()]" -> BlockType.Cylinder
            "((()))" -> BlockType.DoubleCircle
            "[//]" -> BlockType.LeanRight
            "[\\\\]" -> BlockType.LeanLeft
            "[/\\]" -> BlockType.Trapezoid
            "[\\/]" -> BlockType.InverseTrapezoid
            "<[]>" -> BlockType.BlockArrow
            else -> BlockType.Na
        }

        fun edgeStringToEnd(typeString: String): String = when (typeString.trim().lastOrNull()) {
            'x' -> "arrow_cross"
            'o' -> "arrow_circle"
            '>' -> "arrow_point"
            else -> ""
        }

        fun edgeStringToStart(typeString: String): String =
            when (typeString.trim().firstOrNull()) {
                'x' -> "arrow_cross"
                'o' -> "arrow_circle"
                '<' -> "arrow_point"
                else -> "arrow_open"
            }

        fun edgeStringToThickness(typeString: String): String =
            if ("==" in typeString) "thick" else "normal"

        fun edgeStringToPattern(typeString: String): String =
            if (".-" in typeString) "dotted" else "solid"
    }
}
