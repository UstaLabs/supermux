package com.swithun.cmpmermaid.core.packet

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPacketOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.packet.upstream.mermaid.PacketDb
import com.swithun.cmpmermaid.core.packet.upstream.mermaid.PacketWord
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin translation of Mermaid 12.0.0 packet/renderer.ts and packet/styles.ts.
 */
internal class PacketLayout {
    fun layout(
        db: PacketDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        when (val validation = validate(db.config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val config = db.config.withEffectivePadding()
        val totalRowHeight = config.rowHeight + config.paddingY
        val title = db.diagramTitle.orEmpty()
        val rendererHeight = totalRowHeight * (db.getPacket().size + 1) -
            if (title.isNotEmpty()) 0f else config.rowHeight
        val rendererWidth = config.bitWidth * config.bitsPerRow + 2f
        val elements = mutableListOf<SceneElement>()
        db.getPacket().forEachIndexed { rowNumber, word ->
            drawWord(
                word = word,
                rowNumber = rowNumber,
                config = config,
                context = context,
                elements = elements,
            )
        }
        if (title.isNotEmpty()) {
            elements += centeredText(
                id = "packet-title",
                value = title,
                centerX = rendererWidth / 2f,
                centerY = rendererHeight - totalRowHeight / 2f,
                fontSize = TITLE_FONT_SIZE,
                context = context,
                zIndex = elements.size + 1,
            )
        }
        return GMResult.Ok(
            normalizeViewport(
                scene = MermaidScene(
                    width = rendererWidth,
                    height = rendererHeight,
                    background = context.theme.background,
                    elements = elements,
                    title = title.takeIf(String::isNotEmpty),
                    accessibilityTitle = db.accessibilityTitle,
                    accessibilityDescription = db.accessibilityDescription,
                    viewportSizing = if (config.useMaxWidth) {
                        MermaidSceneViewportSizing.ResponsiveMaxWidth
                    } else {
                        MermaidSceneViewportSizing.Intrinsic
                    },
                ),
            ),
        )
    }

    private fun drawWord(
        word: PacketWord,
        rowNumber: Int,
        config: MermaidPacketOptions,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val wordY = rowNumber * (config.rowHeight + config.paddingY) + config.paddingY
        word.forEachIndexed { blockIndex, block ->
            val blockX = (block.start % config.bitsPerRow).toFloat() * config.bitWidth + 1f
            val width = (block.end - block.start + 1L).toFloat() *
                config.bitWidth - config.paddingX
            val id = "packet-$rowNumber-$blockIndex"
            elements += SceneShape(
                id = "$id-block",
                bounds = SceneRect(
                    left = blockX,
                    top = wordY,
                    right = blockX + width,
                    bottom = wordY + config.rowHeight,
                ),
                kind = SceneShapeKind.Rectangle,
                fill = BLOCK_FILL,
                stroke = BLACK,
                strokeWidth = 1f,
                cornerRadius = 0f,
                shadow = null,
                zIndex = elements.size + 1,
            )
            elements += centeredText(
                id = "$id-label",
                value = block.label,
                centerX = blockX + width / 2f,
                centerY = wordY + config.rowHeight / 2f,
                fontSize = LABEL_FONT_SIZE,
                context = context,
                zIndex = elements.size + 1,
            )
            if (config.showBits) {
                val isSingleBlock = block.end == block.start
                val numberY = wordY - 2f
                elements += anchoredText(
                    id = "$id-start",
                    value = block.start.toString(),
                    anchorX = blockX + if (isSingleBlock) width / 2f else 0f,
                    baselineY = numberY,
                    alignment = if (isSingleBlock) {
                        SceneTextAlignment.Center
                    } else {
                        SceneTextAlignment.Start
                    },
                    context = context,
                    zIndex = elements.size + 1,
                )
                if (!isSingleBlock) {
                    elements += anchoredText(
                        id = "$id-end",
                        value = block.end.toString(),
                        anchorX = blockX + width,
                        baselineY = numberY,
                        alignment = SceneTextAlignment.End,
                        context = context,
                        zIndex = elements.size + 1,
                    )
                }
            }
        }
    }

    private fun centeredText(
        id: String,
        value: String,
        centerX: Float,
        centerY: Float,
        fontSize: Float,
        context: MermaidRenderContext,
        zIndex: Int,
    ): SceneText {
        val metrics = measure(value, fontSize, context)
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = centerX - metrics.width / 2f,
                top = centerY - metrics.height / 2f,
                right = centerX + metrics.width / 2f,
                bottom = centerY + metrics.height / 2f,
            ),
            color = BLACK,
            fontSize = fontSize,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun anchoredText(
        id: String,
        value: String,
        anchorX: Float,
        baselineY: Float,
        alignment: SceneTextAlignment,
        context: MermaidRenderContext,
        zIndex: Int,
    ): SceneText {
        val metrics = measure(value, BYTE_FONT_SIZE, context)
        val left = when (alignment) {
            SceneTextAlignment.Start -> anchorX - TEXT_ALIGNMENT_INSET
            SceneTextAlignment.Center -> anchorX - metrics.width / 2f
            SceneTextAlignment.End -> anchorX - metrics.width + TEXT_ALIGNMENT_INSET
        }
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = left,
                top = baselineY - metrics.height,
                right = left + metrics.width,
                bottom = baselineY,
            ),
            color = BLACK,
            fontSize = BYTE_FONT_SIZE,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = alignment,
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun measure(
        value: String,
        fontSize: Float,
        context: MermaidRenderContext,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = value,
            fontSize = fontSize,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
        ),
    )

    private fun normalizeViewport(scene: MermaidScene): MermaidScene {
        val rendererViewport = SceneRect(0f, 0f, scene.width, scene.height)
        val content = scene.elements
            .map { element ->
                when (element) {
                    is SceneShape -> element.bounds
                    is SceneText -> element.bounds
                    else -> rendererViewport
                }
            }
            .reduceOrNull(SceneRect::union)
        val union = content?.let(rendererViewport::union) ?: rendererViewport
        val left = union.left - VIEWPORT_PADDING
        val top = union.top - VIEWPORT_PADDING
        val right = union.right + VIEWPORT_PADDING
        val bottom = union.bottom + VIEWPORT_PADDING
        return scene.copy(
            width = max(1f, right - left),
            height = max(1f, bottom - top),
            elements = scene.elements.map { element ->
                when (element) {
                    is SceneShape -> element.copy(bounds = element.bounds.translate(-left, -top))
                    is SceneText -> element.copy(bounds = element.bounds.translate(-left, -top))
                    else -> element
                }
            },
        )
    }

    private fun validate(
        config: MermaidPacketOptions,
    ): GMResult<Unit, MermaidError> {
        val positive = listOf(
            "rowHeight" to config.rowHeight,
            "bitWidth" to config.bitWidth,
        )
        positive.firstOrNull { (_, value) -> !value.isFinite() || value < 1f }?.let { invalid ->
            return configurationError("${invalid.first} must be at least 1")
        }
        val nonNegative = listOf(
            "paddingX" to config.paddingX,
            "paddingY" to config.paddingY,
        )
        nonNegative.firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
            ?.let { invalid ->
                return configurationError("${invalid.first} must be non-negative")
            }
        if (config.bitsPerRow < 1) {
            return configurationError("bitsPerRow must be at least 1")
        }
        val width = config.bitWidth * config.bitsPerRow
        if (!width.isFinite()) {
            return configurationError("bitWidth * bitsPerRow must be finite")
        }
        return GMResult.Ok(Unit)
    }

    private fun MermaidPacketOptions.withEffectivePadding(): MermaidPacketOptions = copy(
        // Mermaid 12.0.0 packet/db.ts -> getConfig.
        paddingY = paddingY + if (showBits) SHOW_BITS_PADDING else 0f,
    )

    private fun configurationError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Packet $message"))

    private companion object {
        val BLACK = SceneColor(0xFF000000)
        val BLOCK_FILL = SceneColor(0xFFEFEFEF)
        const val BYTE_FONT_SIZE = 10f
        const val LABEL_FONT_SIZE = 12f
        const val TITLE_FONT_SIZE = 14f
        const val SHOW_BITS_PADDING = 10f
        const val TEXT_ALIGNMENT_INSET = 4f
        const val VIEWPORT_PADDING = 12f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
