package com.swithun.cmpmermaid.core.wardley.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor

internal enum class WardleySourceStrategy {
    Build,
    Buy,
    Outsource,
    Market,
}

internal enum class WardleyFlow {
    Forward,
    Backward,
    Bidirectional,
}

internal data class WardleyNode(
    val id: String,
    val label: String,
    val x: Float? = null,
    val y: Float? = null,
    val className: String? = null,
    val labelOffsetX: Float? = null,
    val labelOffsetY: Float? = null,
    val inPipeline: Boolean = false,
    val isPipelineParent: Boolean = false,
    val inertia: Boolean = false,
    val sourceStrategy: WardleySourceStrategy? = null,
)

internal data class WardleyLink(
    val source: String,
    val target: String,
    val dashed: Boolean = false,
    val label: String? = null,
    val flow: WardleyFlow? = null,
)

internal data class WardleyTrend(
    val nodeId: String,
    val targetX: Float,
    val targetY: Float,
)

internal data class WardleyPipeline(
    val nodeId: String,
    val componentIds: List<String>,
)

internal data class WardleyAnnotation(
    val number: Int,
    val coordinates: List<WardleyCoordinate>,
    val text: String? = null,
)

internal data class WardleyCoordinate(
    val x: Float,
    val y: Float,
)

internal data class WardleyNote(
    val text: String,
    val x: Float,
    val y: Float,
)

internal data class WardleyAccelerator(
    val name: String,
    val x: Float,
    val y: Float,
)

internal data class WardleyDeaccelerator(
    val name: String,
    val x: Float,
    val y: Float,
)

internal data class WardleyAxesConfig(
    val xLabel: String? = null,
    val yLabel: String? = null,
    val stages: List<String>? = null,
    val stageBoundaries: List<Float>? = null,
)

internal data class WardleySize(
    val width: Float,
    val height: Float,
)

internal data class WardleyBuildResult(
    val nodes: List<WardleyNode>,
    val links: List<WardleyLink>,
    val trends: List<WardleyTrend>,
    val pipelines: List<WardleyPipeline>,
    val annotations: List<WardleyAnnotation>,
    val notes: List<WardleyNote>,
    val accelerators: List<WardleyAccelerator>,
    val deaccelerators: List<WardleyDeaccelerator>,
    val annotationsBox: WardleyCoordinate?,
    val axes: WardleyAxesConfig,
    val size: WardleySize?,
)

internal data class WardleyDocument(
    val data: WardleyBuildResult,
    val diagramTitle: String?,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
)

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/wardley/wardleyBuilder.ts -> WardleyBuilder.
 */
internal class WardleyBuilder {
    private val nodes = linkedMapOf<String, WardleyNode>()
    private val links = mutableListOf<WardleyLink>()
    private val trends = linkedMapOf<String, WardleyTrend>()
    private val pipelines = linkedMapOf<String, MutableList<String>>()
    private val annotations = mutableListOf<WardleyAnnotation>()
    private val notes = mutableListOf<WardleyNote>()
    private val accelerators = mutableListOf<WardleyAccelerator>()
    private val deaccelerators = mutableListOf<WardleyDeaccelerator>()
    private var annotationsBox: WardleyCoordinate? = null
    private var axes = WardleyAxesConfig()
    private var size: WardleySize? = null

    fun addNode(node: WardleyNode) {
        val existing = nodes[node.id] ?: WardleyNode(
            id = node.id,
            label = node.label,
        )
        nodes[node.id] = existing.copy(
            label = node.label,
            x = node.x ?: existing.x,
            y = node.y ?: existing.y,
            className = node.className ?: existing.className,
            labelOffsetX = node.labelOffsetX ?: existing.labelOffsetX,
            labelOffsetY = node.labelOffsetY ?: existing.labelOffsetY,
            inPipeline = node.inPipeline || existing.inPipeline,
            isPipelineParent = node.isPipelineParent || existing.isPipelineParent,
            inertia = node.inertia || existing.inertia,
            sourceStrategy = node.sourceStrategy ?: existing.sourceStrategy,
        )
    }

    fun addLink(link: WardleyLink) {
        links += link
    }

    fun addTrend(trend: WardleyTrend) {
        trends[trend.nodeId] = trend
    }

    fun startPipeline(nodeId: String) {
        pipelines[nodeId] = mutableListOf()
        nodes[nodeId]?.let { node ->
            nodes[nodeId] = node.copy(isPipelineParent = true)
        }
    }

    fun addPipelineComponent(
        pipelineNodeId: String,
        componentId: String,
    ) {
        pipelines[pipelineNodeId]?.add(componentId)
        nodes[componentId]?.let { node ->
            nodes[componentId] = node.copy(inPipeline = true)
        }
    }

    fun addAnnotation(annotation: WardleyAnnotation) {
        annotations += annotation
    }

    fun addNote(note: WardleyNote) {
        notes += note
    }

    fun addAccelerator(accelerator: WardleyAccelerator) {
        accelerators += accelerator
    }

    fun addDeaccelerator(deaccelerator: WardleyDeaccelerator) {
        deaccelerators += deaccelerator
    }

    fun setAnnotationsBox(
        x: Float,
        y: Float,
    ) {
        annotationsBox = WardleyCoordinate(x = x, y = y)
    }

    fun setAxes(partial: WardleyAxesConfig) {
        axes = axes.copy(
            xLabel = partial.xLabel ?: axes.xLabel,
            yLabel = partial.yLabel ?: axes.yLabel,
            stages = partial.stages ?: axes.stages,
            stageBoundaries = partial.stageBoundaries ?: axes.stageBoundaries,
        )
    }

    fun setSize(
        width: Float,
        height: Float,
    ) {
        size = WardleySize(width = width, height = height)
    }

    fun getNode(id: String): WardleyNode? = nodes[id]

    /**
     * Mermaid.js 12.0.0:
     * wardleyBuilder.ts -> resolveNodeId.
     */
    fun resolveNodeId(name: String): String {
        if (name in nodes) {
            return name
        }
        return nodes.entries.firstOrNull { (_, node) -> node.label == name }?.key ?: name
    }

    /**
     * The upstream builder throws for incomplete nodes. The Kotlin boundary
     * preserves the same validation while exposing it as a structured result.
     */
    fun build(): GMResult<WardleyBuildResult, MermaidError> {
        val incomplete = nodes.values.firstOrNull { node ->
            node.x == null || node.y == null
        }
        if (incomplete != null) {
            return GMResult.Err(
                MermaidError.Layout(
                    "Wardley node \"${incomplete.label}\" is missing coordinates",
                ),
            )
        }
        return GMResult.Ok(
            WardleyBuildResult(
                nodes = nodes.values.toList(),
                links = links.toList(),
                trends = trends.values.toList(),
                pipelines = pipelines.map { (nodeId, componentIds) ->
                    WardleyPipeline(nodeId = nodeId, componentIds = componentIds.toList())
                },
                annotations = annotations.toList(),
                notes = notes.toList(),
                accelerators = accelerators.toList(),
                deaccelerators = deaccelerators.toList(),
                annotationsBox = annotationsBox,
                axes = axes,
                size = size,
            ),
        )
    }
}

internal fun String.decodeWardleyText(): String =
    MermaidPreprocessor.decodeEntities(this)
