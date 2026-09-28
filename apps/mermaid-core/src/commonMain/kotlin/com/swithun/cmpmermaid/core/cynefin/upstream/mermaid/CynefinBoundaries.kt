package com.swithun.cmpmermaid.core.cynefin.upstream.mermaid

import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import kotlin.math.ceil
import kotlin.math.floor

internal data class CynefinBoundaryPath(
    val points: List<ScenePoint>,
    val commands: List<ScenePathCommand>,
)

internal data class CynefinConfusionEllipse(
    val center: ScenePoint,
    val radiusX: Float,
    val radiusY: Float,
)

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/cynefin/cynefinBoundaries.ts.
 */
internal object CynefinBoundaries {
    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> seededRandom.
    fun seededRandom(seed: Double): Double {
        var t = toInt32(seed + 0x6D2B79F5L.toDouble())
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return (t xor (t ushr 14)).toUInt().toDouble() / UINT32_RANGE
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> hashString.
    fun hashString(source: String): Int {
        var hash = 0
        source.forEach { character ->
            hash = (hash shl 5) - hash + character.code
        }
        return hash
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> resolveSeed.
    fun resolveSeed(
        configuredSeed: Float?,
        identity: String,
    ): Double = if (
        configuredSeed != null &&
        configuredSeed.isFinite() &&
        configuredSeed != 0f
    ) {
        configuredSeed.toDouble()
    } else {
        hashString(identity).toDouble()
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> generateFoldPath.
    fun generateFoldPath(
        width: Float,
        height: Float,
        seed: Double,
        amplitudeOverride: Float? = null,
    ): CynefinBoundaryPath {
        val centerX = width.toDouble() / 2.0
        val amplitude = (amplitudeOverride ?: width * 0.015f).toDouble()
        val segmentHeight = height.toDouble() / SEGMENT_COUNT
        val points = (0..SEGMENT_COUNT).map { index ->
            val jitter = seededRandom(seed + index * 17.0) * amplitude * 2.0 - amplitude
            ScenePoint(
                x = (centerX + jitter).toFloat(),
                y = (index * segmentHeight).toFloat(),
            )
        }
        val commands = buildList {
            add(ScenePathCommand.MoveTo(points.first()))
            repeat(points.lastIndex) { index ->
                val start = points[index]
                val end = points[index + 1]
                val middleY = (start.y + end.y) / 2f
                val direction = if (index % 2 == 0) 1.0 else -1.0
                val offset = amplitude * 1.5 * direction *
                    seededRandom(seed + index * 31.0 + 7.0)
                add(
                    ScenePathCommand.CubicTo(
                        control1 = ScenePoint(
                            x = (start.x + offset).toFloat(),
                            y = middleY,
                        ),
                        control2 = ScenePoint(
                            x = (end.x - offset).toFloat(),
                            y = middleY,
                        ),
                        end = end,
                    ),
                )
            }
        }
        return CynefinBoundaryPath(points = points, commands = commands)
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> generateHorizontalBoundary.
    fun generateHorizontalBoundary(
        width: Float,
        height: Float,
        seed: Double,
        amplitudeOverride: Float? = null,
    ): CynefinBoundaryPath {
        val centerY = height.toDouble() / 2.0
        val amplitude = (amplitudeOverride ?: height * 0.015f).toDouble()
        val segmentWidth = width.toDouble() / SEGMENT_COUNT
        val points = (0..SEGMENT_COUNT).map { index ->
            val jitter = seededRandom(seed + index * 23.0) * amplitude * 2.0 - amplitude
            ScenePoint(
                x = (index * segmentWidth).toFloat(),
                y = (centerY + jitter).toFloat(),
            )
        }
        val commands = buildList {
            add(ScenePathCommand.MoveTo(points.first()))
            repeat(points.lastIndex) { index ->
                val start = points[index]
                val end = points[index + 1]
                val middleX = (start.x + end.x) / 2f
                val direction = if (index % 2 == 0) 1.0 else -1.0
                val offset = amplitude * 1.5 * direction *
                    seededRandom(seed + index * 37.0 + 11.0)
                add(
                    ScenePathCommand.CubicTo(
                        control1 = ScenePoint(
                            x = middleX,
                            y = (start.y + offset).toFloat(),
                        ),
                        control2 = ScenePoint(
                            x = middleX,
                            y = (end.y - offset).toFloat(),
                        ),
                        end = end,
                    ),
                )
            }
        }
        return CynefinBoundaryPath(points = points, commands = commands)
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> generateCliffPath.
    fun generateCliffPath(
        width: Float,
        height: Float,
    ): CynefinBoundaryPath {
        val centerX = width / 2f
        val topY = height * 0.5f
        val bottomY = height
        val amplitude = width * 0.03f
        val middle = ScenePoint(
            x = centerX + amplitude * 0.5f,
            y = topY + (bottomY - topY) * 0.75f,
        )
        val end = ScenePoint(centerX, bottomY)
        return CynefinBoundaryPath(
            points = listOf(ScenePoint(centerX, topY), middle, end),
            commands = listOf(
                ScenePathCommand.MoveTo(ScenePoint(centerX, topY)),
                ScenePathCommand.CubicTo(
                    control1 = ScenePoint(
                        x = centerX + amplitude,
                        y = topY + (bottomY - topY) * 0.2f,
                    ),
                    control2 = ScenePoint(
                        x = centerX - amplitude * 1.5f,
                        y = topY + (bottomY - topY) * 0.55f,
                    ),
                    end = middle,
                ),
                ScenePathCommand.CubicTo(
                    control1 = ScenePoint(
                        x = centerX - amplitude,
                        y = topY + (bottomY - topY) * 0.85f,
                    ),
                    control2 = ScenePoint(
                        x = centerX + amplitude * 0.3f,
                        y = topY + (bottomY - topY) * 0.95f,
                    ),
                    end = end,
                ),
            ),
        )
    }

    // Mermaid.js 12.0.0: cynefinBoundaries.ts -> generateConfusionPath.
    fun generateConfusionPath(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
    ): CynefinConfusionEllipse = CynefinConfusionEllipse(
        center = ScenePoint(centerX, centerY),
        radiusX = radiusX,
        radiusY = radiusY,
    )

    private fun toInt32(value: Double): Int {
        if (!value.isFinite() || value == 0.0) {
            return 0
        }
        val integer = if (value > 0.0) floor(value) else ceil(value)
        val modulo = integer % UINT32_RANGE
        val unsigned = if (modulo < 0.0) modulo + UINT32_RANGE else modulo
        return if (unsigned >= INT32_SIGN_BIT) {
            (unsigned - UINT32_RANGE).toInt()
        } else {
            unsigned.toInt()
        }
    }

    private const val SEGMENT_COUNT = 7
    private const val UINT32_RANGE = 4_294_967_296.0
    private const val INT32_SIGN_BIT = 2_147_483_648.0
}
