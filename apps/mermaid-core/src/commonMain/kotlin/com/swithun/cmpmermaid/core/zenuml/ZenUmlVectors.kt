package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.SceneAffineTransform
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePathFillRule
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneShapeViewportFit
import com.swithun.cmpmermaid.core.SceneStrokeCap
import com.swithun.cmpmermaid.core.SceneStrokeJoin
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlFragmentKind

/**
 * @zenuml/core 3.49.2:
 * src/svg/components/message.ts, return.ts and fragment.ts.
 */
internal object ZenUmlVectors {
    val openArrow = arrow(filled = false)
    val filledArrow = arrow(filled = true)

    val selfReturn = ZenUmlVectorDefinition(
        viewBox = SceneRect(0f, 0f, 512f, 512f),
        paths = listOf(
            filled(
                "M256 0C114.84 0 0 114.84 0 256s114.84 256 256 256 " +
                    "256-114.84 256-256S397.16 0 256 0Zm0 469.33c-117.63 0-213.33-95.7-213.33-213.33" +
                    "S138.37 42.67 256 42.67 469.33 138.37 469.33 256 373.63 469.33 256 469.33Z",
            ),
            filled(
                "M288 192h-87.16l27.58-27.58a21.33 21.33 0 1 0-30.17-30.17l-64 64a21.33 " +
                    "21.33 0 0 0 0 30.17l64 64a21.33 21.33 0 0 0 30.17-30.17l-27.58-27.58H288" +
                    "a53.33 53.33 0 0 1 0 106.67h-32a21.33 21.33 0 0 0 0 42.66h32a96 96 0 0 0 0-192Z",
            ),
        ),
        viewportFit = SceneShapeViewportFit.MeetCenter,
    )

    fun fragment(kind: ZenUmlFragmentKind): ZenUmlVectorDefinition = when (kind) {
        ZenUmlFragmentKind.Loop -> vector(
            viewBoxSize = 1024f,
            filled(
                "M960 101.84l-896.002.002c-35.344 0-64 28.656-64 64v576c0 35.36 28.656 64 64 64h160" +
                    "c20.496 0 32-26.32 32-31.984v-.016c0-5.824-10.88-32.416-32-32.416h-120.96" +
                    "c-21.376 0-38.72-17.344-38.72-38.72V206.002c0-21.391 17.328-38.72 38.72-38.72" +
                    "l818.272-1.007c21.376 0 38.72 17.328 38.72 38.72V702.69c0 21.376-17.344 38.72-38.72 38.72" +
                    "H518.142l75.984-68.912c9.344-8.944 12.369-23.408 3.025-32.336l-5.472-8.064" +
                    "c-9.376-8.945-24.496-8.945-33.84 0L428.111 750.53c-.192.16-.368.224-.528.368" +
                    "l-8.48 8.096c-4.672 4.431-7.008 10.335-6.976 16.223-.032 5.904 2.288 11.777 6.977 16.288" +
                    "l8.48 8.096c.16.16.368.192.528.336L555.84 915.44c9.344 8.944 24.464 8.944 33.84 0" +
                    "l5.472-8.065c9.344-8.944 6.32-23.44-3.025-32.368l-77.135-69.168H960" +
                    "c35.343 0 64-28.64 64-64v-576c0-35.344-28.657-64-64-64z",
            ),
        )
        ZenUmlFragmentKind.Opt -> vector(
            viewBoxSize = 24f,
            stroked(
                pathData = "M12 5A7 7 0 1 0 12 19A7 7 0 1 0 12 5",
                dashIntervals = listOf(3f, 2f),
            ),
        )
        ZenUmlFragmentKind.Par -> vector(
            viewBoxSize = 24f,
            stroked("M5 10H19"),
            stroked("M5 14H19"),
        )
        ZenUmlFragmentKind.Critical -> vector(
            viewBoxSize = 24f,
            stroked("M12 5L19 12L12 19L5 12L12 5Z"),
            stroked("M12 9V13"),
            stroked("M12 15V15.5"),
        )
        ZenUmlFragmentKind.TryCatchFinally -> ZenUmlVectorDefinition(
            viewBox = SceneRect(0f, 0f, 76f, 76f),
            paths = listOf(
                filled(
                    "M 26,22.0001L 27,21.9998L 27,27L 26.0001,27.0003C 23.2386,27.0003 " +
                        "21.0001,29.2389 21.0001,32.0003L 21,46.0002C 21,48.7616 23.2386,51.0002 " +
                        "25.9999,51.0002L 27,51.0002L 27,47L 33.75,53.5L 27,60L 27,56L 26,56C " +
                        "20.4771,56 16,51.5229 16,46L 16,32.0001C 16,26.4773 20.4771,22.0001 " +
                        "26,22.0001 Z M 33,27L 59,27L 59,32L 33,32L 33,27 Z M 36,35L 59,35L " +
                        "59,40L 36,40L 36,35 Z M 33,43L 59,43L 59,48L 33,48L 33,43 Z",
                ),
            ),
            viewportFit = SceneShapeViewportFit.MeetCenter,
        )
        ZenUmlFragmentKind.Ref -> vector(
            viewBoxSize = 24f,
            stroked("M10 6H18C19.1046 6 20 6.89543 20 8V16C20 17.1046 19.1046 18 18 18H10"),
            stroked("M10 6L6 12L10 18"),
        )
        ZenUmlFragmentKind.Section -> ZenUmlVectorDefinition(
            viewBox = SceneRect(0f, 0f, 15f, 15f),
            paths = listOf(
                filled(
                    "M2 1.5C2 1.77614 1.77614 2 1.5 2C1.22386 2 1 1.77614 1 1.5C1 1.22386 " +
                        "1.22386 1 1.5 1C1.77614 1 2 1.22386 2 1.5ZM2 5L2 10H13V5H2ZM2 4C1.44772 4 " +
                        "1 4.44772 1 5V10C1 10.5523 1.44772 11 2 11H13C13.5523 11 14 10.5523 14 10V5" +
                        "C14 4.44772 13.5523 4 13 4H2ZM1.5 14C1.77614 14 2 13.7761 2 13.5C2 13.2239 " +
                        "1.77614 13 1.5 13C1.22386 13 1 13.2239 1 13.5C1 13.7761 1.22386 14 1.5 14Z" +
                        "M4 1.5C4 1.77614 3.77614 2 3.5 2C3.22386 2 3 1.77614 3 1.5C3 1.22386 " +
                        "3.22386 1 3.5 1C3.77614 1 4 1.22386 4 1.5ZM3.5 14C3.77614 14 4 13.7761 4 13.5" +
                        "C4 13.2239 3.77614 13 3.5 13C3.22386 13 3 13.2239 3 13.5C3 13.7761 3.22386 14 3.5 14Z" +
                        "M6 1.5C6 1.77614 5.77614 2 5.5 2C5.22386 2 5 1.77614 5 1.5C5 1.22386 " +
                        "5.22386 1 5.5 1C5.77614 1 6 1.22386 6 1.5ZM5.5 14C5.77614 14 6 13.7761 6 13.5" +
                        "C6 13.2239 5.77614 13 5.5 13C5.22386 13 5 13.2239 5 13.5C5 13.7761 5.22386 14 5.5 14Z" +
                        "M8 1.5C8 1.77614 7.77614 2 7.5 2C7.22386 2 7 1.77614 7 1.5C7 1.22386 " +
                        "7.22386 1 7.5 1C7.77614 1 8 1.22386 8 1.5ZM7.5 14C7.77614 14 8 13.7761 8 13.5" +
                        "C8 13.2239 7.77614 13 7.5 13C7.22386 13 7 13.2239 7 13.5C7 13.7761 7.22386 14 7.5 14Z" +
                        "M10 1.5C10 1.77614 9.77614 2 9.5 2C9.22386 2 9 1.77614 9 1.5C9 1.22386 " +
                        "9.22386 1 9.5 1C9.77614 1 10 1.22386 10 1.5ZM9.5 14C9.77614 14 10 13.7761 10 13.5" +
                        "C10 13.2239 9.77614 13 9.5 13C9.22386 13 9 13.2239 9 13.5C9 13.7761 9.22386 14 9.5 14Z" +
                        "M12 1.5C12 1.77614 11.7761 2 11.5 2C11.2239 2 11 1.77614 11 1.5C11 1.22386 " +
                        "11.2239 1 11.5 1C11.7761 1 12 1.22386 12 1.5ZM11.5 14C11.7761 14 12 13.7761 12 13.5" +
                        "C12 13.2239 11.7761 13 11.5 13C11.2239 13 11 13.2239 11 13.5C11 13.7761 11.2239 14 11.5 14Z" +
                        "M14 1.5C14 1.77614 13.7761 2 13.5 2C13.2239 2 13 1.77614 13 1.5C13 1.22386 " +
                        "13.2239 1 13.5 1C13.7761 1 14 1.22386 14 1.5ZM13.5 14C13.7761 14 14 13.7761 14 13.5" +
                        "C14 13.2239 13.7761 13 13.5 13C13.2239 13 13 13.2239 13 13.5C13 13.7761 " +
                        "13.2239 14 13.5 14Z",
                    fillRule = ScenePathFillRule.EvenOdd,
                ),
            ),
            viewportFit = SceneShapeViewportFit.MeetCenter,
        )
        ZenUmlFragmentKind.Alt -> vector(
            viewBoxSize = 24f,
            stroked("M12 8L20 12L12 16L4 12L12 8Z"),
        )
    }

    fun arrow(pointsLeft: Boolean, filled: Boolean): ZenUmlVectorDefinition {
        val base = if (filled) filledArrow else openArrow
        if (!pointsLeft) return base
        return base.copy(
            paths = base.paths.map { path ->
                path.copy(
                    transform = SceneAffineTransform(
                        scaleX = -1f,
                        translateX = 7f,
                    ),
                )
            },
        )
    }

    fun openTipArrow(pointsLeft: Boolean): ZenUmlVectorDefinition = ZenUmlVectorDefinition(
        viewBox = SceneRect(0f, 0f, 5.15f, 6.5f),
        paths = listOf(
            SceneShapePath(
                pathData = if (pointsLeft) {
                    "M5.15 0L0 3.25L5.15 6.5"
                } else {
                    "M0 0L5.15 3.25L0 6.5"
                },
                fill = SceneShapePaint.None,
                stroke = SceneShapePaint.Stroke,
                strokeColor = BLACK,
                strokeWidth = 2f,
                strokeCap = SceneStrokeCap.Round,
            ),
        ),
        viewportFit = SceneShapeViewportFit.Stretch,
    )

    fun selfCall(async: Boolean): ZenUmlVectorDefinition = ZenUmlVectorDefinition(
        viewBox = SceneRect(0f, 0f, 30f, 24f),
        paths = listOf(
            SceneShapePath(
                pathData = if (async) {
                    "M0 2L26 2Q28 2 28 4L28 13Q28 15 26 15L1 15"
                } else {
                    "M0 2L26 2Q28 2 28 4L28 13Q28 15 26 15L14 15"
                },
                fill = SceneShapePaint.None,
                stroke = SceneShapePaint.Stroke,
                strokeColor = BLACK,
                strokeWidth = 2f,
            ),
            (if (async) openArrow.paths.single() else filledArrow.paths.single()).copy(
                transform = SceneAffineTransform(
                    scaleX = -1f,
                    scaleY = 1f,
                    translateX = if (async) 7f else 14f,
                    translateY = 10.5f,
                ),
            ),
        ),
        viewportFit = SceneShapeViewportFit.Stretch,
    )

    private fun arrow(filled: Boolean): ZenUmlVectorDefinition = ZenUmlVectorDefinition(
        viewBox = SceneRect(0f, 0f, 7f, 9f),
        paths = listOf(
            SceneShapePath(
                pathData = if (filled) {
                    "M1 1.25L6.15 4.5L1 7.75Z"
                } else {
                    "M1 1.25L6.15 4.5L1 7.75"
                },
                fill = if (filled) SceneShapePaint.Fill else SceneShapePaint.None,
                stroke = SceneShapePaint.Stroke,
                fillColor = BLACK,
                strokeColor = BLACK,
                strokeWidth = 2f,
                strokeCap = SceneStrokeCap.Round,
            ),
        ),
        viewportFit = SceneShapeViewportFit.MeetCenter,
    )

    private fun vector(
        viewBoxSize: Float,
        vararg paths: SceneShapePath,
    ): ZenUmlVectorDefinition = ZenUmlVectorDefinition(
        viewBox = SceneRect(0f, 0f, viewBoxSize, viewBoxSize),
        paths = paths.toList(),
        viewportFit = SceneShapeViewportFit.MeetCenter,
    )

    private fun filled(
        pathData: String,
        fillRule: ScenePathFillRule = ScenePathFillRule.NonZero,
    ): SceneShapePath = SceneShapePath(
        pathData = pathData,
        fill = SceneShapePaint.Fill,
        stroke = SceneShapePaint.None,
        fillColor = BLACK,
        fillRule = fillRule,
    )

    private fun stroked(
        pathData: String,
        dashIntervals: List<Float> = emptyList(),
    ): SceneShapePath = SceneShapePath(
        pathData = pathData,
        fill = SceneShapePaint.None,
        stroke = SceneShapePaint.Stroke,
        strokeColor = BLACK,
        strokeWidth = 1.5f,
        strokeCap = SceneStrokeCap.Round,
        strokeJoin = SceneStrokeJoin.Round,
        strokePattern = if (dashIntervals.isEmpty()) {
            SceneStrokePattern.Solid
        } else {
            SceneStrokePattern.Dashed
        },
        dashIntervals = dashIntervals,
    )

    private val BLACK = SceneColor(0xFF000000)
}
