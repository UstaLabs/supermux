package com.swithun.cmpmermaid.core.packet

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPacketOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PacketLayoutTest {
    private val engine = MermaidEngine()
    private val textMetrics = TextMetricProvider { request ->
        TextMetrics(
            width = request.text.length * request.fontSize * 0.55f,
            height = request.fontSize,
        )
    }

    @Test
    fun rendersFixedBitGridUsingUpstreamGeometryAndStyles() {
        val scene = render(
            """
            packet
              0: "Flag"
              1-7: "Code"
              8-31: "Payload"
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertEquals(3, shapes.size)
        assertEquals(27f, shapes[0].bounds.width)
        assertEquals(219f, shapes[1].bounds.width)
        assertEquals(763f, shapes[2].bounds.width)
        assertEquals(32f, shapes[0].bounds.height)
        assertEquals(SceneColor(0xFFEFEFEF), shapes[0].fill)
        assertEquals(SceneColor(0xFF000000), shapes[0].stroke)
        assertEquals(1f, shapes[0].strokeWidth)
        assertEquals(3, texts.count { text -> text.fontSize == 12f })
        assertEquals(5, texts.count { text -> text.fontSize == 10f })
        assertEquals(1050f, scene.width)
        assertEquals(86f, scene.height)
    }

    @Test
    fun splitsWideFieldsAcrossRowsAndPlacesTitleAfterRows() {
        val scene = render(
            """
            packet-beta
              title Wide packet
              +64: "Payload"
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val title = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "Wide packet" }

        assertEquals(2, shapes.size)
        assertEquals(shapes[0].bounds.left, shapes[1].bounds.left)
        assertTrue(shapes[1].bounds.top > shapes[0].bounds.bottom)
        assertTrue(title.bounds.top > shapes[1].bounds.bottom)
        assertEquals(165f, scene.height)
    }

    @Test
    fun appliesPacketConfigurationAndIntrinsicSizing() {
        val scene = render(
            """
            ---
            config:
              packet:
                rowHeight: 24
                bitWidth: 20
                bitsPerRow: 8
                showBits: false
                paddingX: 2
                paddingY: 3
                useMaxWidth: false
            ---
            packet
              +8: "Byte"
            """.trimIndent(),
        )
        val block = scene.elements.filterIsInstance<SceneShape>().single()

        assertEquals(158f, block.bounds.width)
        assertEquals(24f, block.bounds.height)
        assertEquals(186f, scene.width)
        assertEquals(54f, scene.height)
        assertEquals(
            0,
            scene.elements.filterIsInstance<SceneText>().count { text -> text.fontSize == 10f },
        )
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun carriesFrontmatterAndDslAccessibilityMetadata() {
        val scene = render(
            """
            ---
            title: Framed packet
            ---
            packet
              accTitle: Packet structure
              accDescr: Header fields
              0: "Flag"
            """.trimIndent(),
        )

        assertEquals("Framed packet", scene.title)
        assertEquals("Packet structure", scene.accessibilityTitle)
        assertEquals("Header fields", scene.accessibilityDescription)
    }

    @Test
    fun returnsConfigurationErrorsForInvalidDirectOptions() {
        val invalid = listOf(
            MermaidPacketOptions(rowHeight = 0f),
            MermaidPacketOptions(bitWidth = Float.NaN),
            MermaidPacketOptions(bitsPerRow = 0),
            MermaidPacketOptions(paddingX = -1f),
            MermaidPacketOptions(paddingY = Float.POSITIVE_INFINITY),
        )

        invalid.forEach { packet ->
            val result = engine.render(
                "packet\n  0: \"Flag\"",
                context(packet),
            )
            assertIs<MermaidError.Configuration>(
                assertIs<GMResult.Err<MermaidError>>(result).error,
            )
        }
    }

    @Test
    fun acceptsSchemaValidPaddingThatExceedsBitWidth() {
        val result = engine.render(
            "packet\n  0: \"Flag\"",
            context(
                MermaidPacketOptions(
                    bitWidth = 8f,
                    paddingX = 9f,
                ),
            ),
        )

        assertIs<GMResult.Ok<MermaidScene>>(result)
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context()),
            source,
        ).value

    private fun context(
        packet: MermaidPacketOptions = MermaidPacketOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = textMetrics,
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(packet = packet),
    )
}
