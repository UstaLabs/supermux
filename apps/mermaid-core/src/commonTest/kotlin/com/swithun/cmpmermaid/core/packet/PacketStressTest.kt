package com.swithun.cmpmermaid.core.packet

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PacketStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.55f,
                height = request.fontSize,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun renders256DeterministicRandomizedPackets() {
        val random = Random(12_00_14)
        repeat(RANDOM_CASE_COUNT) { caseIndex ->
            val generated = randomPacket(random, caseIndex)
            val first = render(generated.source)
            val second = render(generated.source)

            assertEquals(first, second, "Packet stress case $caseIndex was not deterministic")
            validateScene(first, generated.segmentCount, generated.source)
        }
    }

    private fun randomPacket(
        random: Random,
        caseIndex: Int,
    ): GeneratedPacket {
        val bitsPerRow = BITS_PER_ROW_OPTIONS[caseIndex.mod(BITS_PER_ROW_OPTIONS.size)]
        val fieldCount = random.nextInt(from = 2, until = 18)
        var nextBit = 0
        var segmentCount = 0
        val source = buildString {
            appendLine("---")
            appendLine("config:")
            appendLine("  theme: ${THEMES[caseIndex.mod(THEMES.size)]}")
            appendLine("  packet:")
            appendLine("    rowHeight: ${24 + caseIndex % 17}")
            appendLine("    bitWidth: ${12 + caseIndex % 13}")
            appendLine("    bitsPerRow: $bitsPerRow")
            appendLine("    showBits: ${caseIndex % 3 != 0}")
            appendLine("    paddingX: ${caseIndex % 5}")
            appendLine("    paddingY: ${caseIndex % 8}")
            appendLine("    useMaxWidth: ${caseIndex % 2 == 0}")
            appendLine("---")
            appendLine(if (caseIndex % 2 == 0) "packet" else "packet-beta")
            appendLine("  title Packet matrix $caseIndex")
            appendLine("  accTitle: Accessible packet $caseIndex")
            repeat(fieldCount) { fieldIndex ->
                val bits = random.nextInt(from = 1, until = bitsPerRow * 2 + 1)
                val rowOffset = nextBit.mod(bitsPerRow)
                segmentCount += (rowOffset + bits + bitsPerRow - 1) / bitsPerRow
                val label = if (fieldIndex % 5 == 0) {
                    "Field $caseIndex-$fieldIndex 東京"
                } else {
                    "Field $caseIndex-$fieldIndex"
                }
                if (fieldIndex % 3 == 0) {
                    val end = nextBit + bits - 1
                    if (bits == 1) {
                        appendLine("  $nextBit: \"$label\"")
                    } else {
                        appendLine("  $nextBit-$end: \"$label\"")
                    }
                } else {
                    appendLine("  +$bits: \"$label\"")
                }
                nextBit += bits
            }
        }
        return GeneratedPacket(source = source, segmentCount = segmentCount)
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            "Expected randomized Packet render success:\n$source",
        ).value

    private fun validateScene(
        scene: MermaidScene,
        expectedSegments: Int,
        source: String,
    ) {
        assertTrue(scene.width.isFinite() && scene.width > 0f, source)
        assertTrue(scene.height.isFinite() && scene.height > 0f, source)
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        assertEquals(expectedSegments, shapes.size, source)
        assertEquals(shapes.size, shapes.map(SceneShape::id).distinct().size, source)
        assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), source)
        scene.elements.forEach { element ->
            when (element) {
                is SceneAsset -> assertValid(element.bounds, source)
                is SceneShape -> assertValid(element.bounds, source)
                is SceneText -> assertValid(element.bounds, source)
                is ScenePath -> assertTrue(
                    element.points.all { point -> point.x.isFinite() && point.y.isFinite() },
                    source,
                )
            }
        }
    }

    private fun assertValid(
        bounds: SceneRect,
        source: String,
    ) {
        assertTrue(bounds.left.isFinite(), source)
        assertTrue(bounds.top.isFinite(), source)
        assertTrue(bounds.right.isFinite(), source)
        assertTrue(bounds.bottom.isFinite(), source)
        assertTrue(bounds.width >= 0f, source)
        assertTrue(bounds.height >= 0f, source)
    }

    private data class GeneratedPacket(
        val source: String,
        val segmentCount: Int,
    )

    private companion object {
        const val RANDOM_CASE_COUNT = 256
        val BITS_PER_ROW_OPTIONS = listOf(8, 16, 24, 32, 48, 64)
        val THEMES = listOf(
            "default",
            "dark",
            "forest",
            "neutral",
            "base",
            "neo",
            "neo-dark",
            "redux",
            "redux-color",
            "redux-dark",
            "redux-dark-color",
        )
    }
}
