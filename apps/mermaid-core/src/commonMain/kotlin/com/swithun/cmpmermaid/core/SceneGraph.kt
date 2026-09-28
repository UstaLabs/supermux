package com.swithun.cmpmermaid.core

data class SceneColor(
    val argb: Long,
)

enum class SceneShapeKind {
    Rectangle,
    RoundedRectangle,
    CollapsedGroup,
    Stadium,
    Subroutine,
    Cylinder,
    Circle,
    DoubleCircle,
    Diamond,
    Hexagon,
    Parallelogram,
    ParallelogramAlt,
    Trapezoid,
    TrapezoidAlt,
    Asymmetric,
    Ellipse,
    TextBlock,
    NotchedRectangle,
    LinedRectangle,
    SmallCircle,
    FramedCircle,
    ForkJoin,
    Hourglass,
    BraceLeft,
    BraceRight,
    Braces,
    Bolt,
    Document,
    Delay,
    DirectAccessStorage,
    LinedCylinder,
    CurvedTrapezoid,
    DividedRectangle,
    Triangle,
    WindowPane,
    FilledCircle,
    LinedDocument,
    NotchedPentagon,
    FlippedTriangle,
    SlopedRectangle,
    MultiDocument,
    MultiProcess,
    PaperTape,
    BowTieRectangle,
    CrossedCircle,
    TaggedDocument,
    TaggedRectangle,
    Icon,
    IconCircle,
    IconSquare,
    IconRounded,
    Image,
    Datastore,
    Folder,
    Bucket,
    Console,
    Browser,
    Person,
    Bang,
    Cloud,
}

enum class SceneStrokePattern {
    Solid,
    Dashed,
    Dotted,
}

enum class SceneBlendMode {
    SourceOver,
    Multiply,
}

enum class SceneArrowHead {
    None,
    Triangle,
    Circle,
    Cross,
    Open,
    Async,
    SequenceCross,
    HalfTriangleTop,
    HalfTriangleBottom,
    HalfOpenTop,
    HalfOpenBottom,
    ClassAggregation,
    ClassExtension,
    ClassComposition,
    ClassDependency,
    ClassLollipop,
    ErOnlyOne,
    ErZeroOrOne,
    ErOneOrMore,
    ErZeroOrMore,
    RequirementArrow,
    RequirementContains,
}

sealed interface SceneElement {
    val zIndex: Int
}

data class SceneShadow(
    val color: SceneColor,
    val offsetX: Float,
    val offsetY: Float,
    val blurRadius: Float = 0f,
)

data class SceneLinearGradient(
    val startColor: SceneColor,
    val endColor: SceneColor,
    val start: ScenePoint = ScenePoint(0f, 0f),
    val end: ScenePoint = ScenePoint(1f, 0f),
    val colorStops: List<SceneGradientStop> = emptyList(),
)

data class SceneGradientStop(
    val offset: Float,
    val color: SceneColor,
)

data class SceneShape(
    val id: String,
    val bounds: SceneRect,
    val kind: SceneShapeKind,
    val geometry: SceneShapeGeometry? = null,
    val fill: SceneColor,
    val stroke: SceneColor,
    val strokeWidth: Float = 1.5f,
    val strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
    val dashIntervals: List<Float> = emptyList(),
    val cornerRadius: Float = 8f,
    val shadow: SceneShadow? = null,
    val strokeGradient: SceneLinearGradient? = null,
    override val zIndex: Int = 10,
) : SceneElement

enum class SceneAssetKind {
    Icon,
    Image,
}

data class SceneAsset(
    val id: String,
    val source: String,
    val bounds: SceneRect,
    val kind: SceneAssetKind,
    val tint: SceneColor? = null,
    override val zIndex: Int = 15,
) : SceneElement

enum class SceneShapePaint {
    None,
    Fill,
    Stroke,
}

enum class ScenePathFillRule {
    NonZero,
    EvenOdd,
}

enum class SceneStrokeCap {
    Butt,
    Round,
    Square,
}

enum class SceneStrokeJoin {
    Miter,
    Round,
    Bevel,
}

enum class SceneShapeViewportFit {
    Stretch,
    MeetStart,
    MeetCenter,
}

data class SceneAffineTransform(
    val scaleX: Float = 1f,
    val skewY: Float = 0f,
    val skewX: Float = 0f,
    val scaleY: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
)

data class SceneShapePath(
    val points: List<ScenePoint> = emptyList(),
    val closed: Boolean = true,
    val fill: SceneShapePaint = SceneShapePaint.Fill,
    val stroke: SceneShapePaint = SceneShapePaint.Stroke,
    val strokeWidth: Float? = null,
    val strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
    val dashIntervals: List<Float> = emptyList(),
    val opacity: Float = 1f,
    val pathData: String? = null,
    val fillRule: ScenePathFillRule = ScenePathFillRule.NonZero,
    val fillColor: SceneColor? = null,
    val fillGradient: SceneLinearGradient? = null,
    val strokeColor: SceneColor? = null,
    val strokeCap: SceneStrokeCap = SceneStrokeCap.Butt,
    val strokeJoin: SceneStrokeJoin = SceneStrokeJoin.Miter,
    val transform: SceneAffineTransform? = null,
)

/**
 * Shape paths use coordinates relative to the center of [SceneShape.bounds].
 * The outline is the exact Mermaid intersection boundary for the rendered node.
 */
data class SceneShapeGeometry(
    val paths: List<SceneShapePath>,
    val outline: List<ScenePoint>,
    val viewBox: SceneRect? = null,
    val viewportFit: SceneShapeViewportFit = SceneShapeViewportFit.Stretch,
)

data class SceneText(
    val text: String,
    val bounds: SceneRect,
    val color: SceneColor,
    val fontSize: Float,
    val lineHeight: Float = 1.2f,
    val fontFamily: String? = null,
    val weight: SceneTextWeight = SceneTextWeight.Medium,
    val italic: Boolean = false,
    val spans: List<SceneTextSpan> = emptyList(),
    val horizontalAlignment: SceneTextAlignment = SceneTextAlignment.Center,
    val rotationDegrees: Float = 0f,
    val rotationPivot: ScenePoint? = null,
    override val zIndex: Int = 20,
    val softWrap: Boolean = true,
    val clipToBounds: Boolean = false,
    val horizontalScale: Float? = null,
    val outlineColor: SceneColor? = null,
    val outlineWidth: Float = 0f,
) : SceneElement

data class SceneTextSpan(
    val start: Int,
    val end: Int,
    val weight: SceneTextWeight? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    val color: SceneColor? = null,
    val background: SceneColor? = null,
    val fontFamily: SceneTextFontFamily? = null,
    val baselineShift: SceneTextBaselineShift? = null,
    val fontSizeScale: Float = 1f,
)

enum class SceneTextWeight {
    Normal,
    Medium,
    Bold,
}

enum class SceneTextFontFamily {
    Monospace,
}

enum class SceneTextBaselineShift {
    Subscript,
    Superscript,
}

enum class SceneTextAlignment {
    Start,
    Center,
    End,
}

sealed interface ScenePathCommand {
    data class MoveTo(
        val point: ScenePoint,
    ) : ScenePathCommand

    data class LineTo(
        val point: ScenePoint,
    ) : ScenePathCommand

    data class QuadraticTo(
        val control: ScenePoint,
        val end: ScenePoint,
    ) : ScenePathCommand

    data class CubicTo(
        val control1: ScenePoint,
        val control2: ScenePoint,
        val end: ScenePoint,
    ) : ScenePathCommand

    data class ArcTo(
        val radius: Float,
        val end: ScenePoint,
        val clockwise: Boolean,
    ) : ScenePathCommand
}

data class ScenePath(
    val id: String,
    val points: List<ScenePoint>,
    val commands: List<ScenePathCommand>,
    val color: SceneColor,
    val strokeWidth: Float,
    val strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
    val arrowStart: SceneArrowHead = SceneArrowHead.None,
    val arrowEnd: SceneArrowHead = SceneArrowHead.None,
    val arrowColor: SceneColor? = null,
    val curve: String = "rounded",
    val look: String,
    val animated: Boolean,
    val animationDurationMillis: Int? = null,
    override val zIndex: Int = 5,
    val dashIntervals: List<Float> = emptyList(),
    val markerBackground: SceneColor? = null,
    val fillColor: SceneColor? = null,
    val closed: Boolean = false,
    val strokeGradient: SceneLinearGradient? = null,
    val opacity: Float = 1f,
    val blendMode: SceneBlendMode = SceneBlendMode.SourceOver,
) : SceneElement

data class SceneNodeInteraction(
    val nodeId: String,
    val bounds: SceneRect,
    val link: String? = null,
    val linkTarget: String? = null,
    val tooltip: String? = null,
    val callbackName: String? = null,
    val callbackArgs: String? = null,
)

enum class MermaidSceneViewportSizing {
    Fit,
    ResponsiveMaxWidth,
    Intrinsic,
}

data class MermaidScene(
    val width: Float,
    val height: Float,
    val background: SceneColor,
    val elements: List<SceneElement>,
    val title: String? = null,
    val accessibilityTitle: String? = null,
    val accessibilityDescription: String? = null,
    val interactions: List<SceneNodeInteraction> = emptyList(),
    val viewportPadding: Float = 0f,
    val viewportSizing: MermaidSceneViewportSizing = MermaidSceneViewportSizing.Fit,
)

data class TextMetricsRequest(
    val text: String,
    val fontSize: Float,
    val maxWidth: Float,
    val lineHeight: Float = 1.2f,
    val fontFamily: String? = null,
    val weight: SceneTextWeight = SceneTextWeight.Medium,
    val spans: List<SceneTextSpan> = emptyList(),
    val horizontalScale: Float? = null,
)

data class TextMetrics(
    val width: Float,
    val height: Float,
    val lineCount: Int? = null,
)

fun interface TextMetricProvider {
    fun measure(request: TextMetricsRequest): TextMetrics
}
