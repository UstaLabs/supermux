package com.swithun.cmpmermaid.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialFlowchartDocumentationTest {
    private val textMetrics = TextMetricProvider { request ->
        val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
        val lineCount = ceil(request.text.length.toDouble() / charactersPerLine)
            .toInt()
            .coerceAtLeast(1)
        TextMetrics(
            width = minOf(request.maxWidth, request.text.length * 8f),
            height = lineCount * 18f,
        )
    }
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = textMetrics,
        options = MermaidRenderOptions(layout = "dagre"),
    )
    private val expectedUnsupportedFeatures = mapOf(
        "112_basic_support_for_fontawesome" to "FontAwesome label icon",
        "113_custom_icons" to "FontAwesome label icon",
    )

    @Test
    fun handlesEveryMermaid12FlowchartDocumentationExample() {
        val failures = officialFlowchartDocumentationCases.mapNotNull {
            renderFailure(it, context)
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Flowchart documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    @Test
    fun retainsOfficialIconAndImageAssetsInSceneGraph() {
        val iconScene = renderCase("069_icon_shape")
        val imageScene = renderCase("070_parameters")

        val icon = iconScene.elements.filterIsInstance<SceneAsset>().single()
        val image = imageScene.elements.filterIsInstance<SceneAsset>().single()

        assertEquals(SceneAssetKind.Icon, icon.kind)
        assertEquals("fa:user", icon.source)
        assertEquals(SceneAssetKind.Image, image.kind)
        assertEquals("https://mermaid.js.org/favicon.svg", image.source)
    }

    @Test
    fun disablesCallbacksButRetainsLinksAndTooltipsAtDefaultStrictSecurity() {
        val scene = renderCase("104_interaction")
        val interactions = scene.interactions.associateBy(SceneNodeInteraction::nodeId)

        assertNull(interactions.getValue("A").callbackName)
        assertEquals("Tooltip for a callback", interactions.getValue("A").tooltip)
        assertEquals("https://www.github.com", interactions.getValue("B").link)
        assertEquals("This is a tooltip for a link", interactions.getValue("B").tooltip)
        assertNull(interactions.getValue("C").callbackName)
        assertEquals("Tooltip for a callback", interactions.getValue("C").tooltip)
        assertEquals("https://www.github.com", interactions.getValue("D").link)
    }

    @Test
    fun retainsOfficialCallbacksAtLooseSecurity() {
        val scene = renderCase(
            id = "104_interaction",
            renderContext = context.copy(
                options = context.options.copy(
                    securityLevel = MermaidSecurityLevel.Loose,
                ),
            ),
        )
        val interactions = scene.interactions.associateBy(SceneNodeInteraction::nodeId)

        assertEquals("callback", interactions.getValue("A").callbackName)
        assertEquals("callback", interactions.getValue("C").callbackName)
        assertEquals("https://www.github.com", interactions.getValue("B").link)
        assertEquals("https://www.github.com", interactions.getValue("D").link)
    }

    private fun renderCase(
        id: String,
        renderContext: MermaidRenderContext = context,
    ): MermaidScene {
        val case = officialFlowchartDocumentationCases.single { it.id == id }
        val result = engine.render(case.source, renderContext)
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }

    private fun renderFailure(
        case: MermaidFlowchartDocCase,
        renderContext: MermaidRenderContext,
    ): String? {
        val expectedFeature = expectedUnsupportedFeatures[case.id]
        return when (val result = engine.render(case.source, renderContext)) {
            is GMResult.Ok -> expectedFeature?.let {
                "${case.id}: expected explicit unsupported feature '$it'"
            } ?: validateScene(case.id, result.value)
            is GMResult.Err -> {
                val error = result.error
                if (error is MermaidError.UnsupportedFeature && error.feature == expectedFeature) {
                    null
                } else {
                    "${case.id}: ${error.message}"
                }
            }
        }
    }

    private fun validateScene(caseId: String, scene: MermaidScene): String? {
        if (!scene.width.isFinite() || !scene.height.isFinite() ||
            scene.width <= 0f || scene.height <= 0f
        ) {
            return "$caseId: invalid scene size ${scene.width} x ${scene.height}"
        }
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null &&
                (!bounds.left.isFinite() || !bounds.top.isFinite() ||
                    !bounds.width.isFinite() || !bounds.height.isFinite() ||
                    bounds.width < 0f || bounds.height < 0f)
            ) {
                return "$caseId: invalid ${element::class.simpleName} bounds $bounds"
            }
            if (element is ScenePath &&
                (element.points.size < 2 ||
                    element.points.any { !it.x.isFinite() || !it.y.isFinite() })
            ) {
                return "$caseId: invalid edge path ${element.id}"
            }
            if (element is SceneShape) {
                val geometry = element.geometry ?: return@forEach
                if (geometry.outline.size < 3 ||
                    geometry.outline.any { !it.x.isFinite() || !it.y.isFinite() } ||
                    geometry.paths.any { path ->
                        path.points.any { !it.x.isFinite() || !it.y.isFinite() }
                    }
                ) {
                    return "$caseId: invalid shape geometry ${element.id}"
                }
            }
        }
        return null
    }
}
