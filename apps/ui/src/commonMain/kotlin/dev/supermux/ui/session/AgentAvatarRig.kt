// The avatar "rig" (format v2, src/core/agents/avatar/FORMAT.md): a stack of vector parts with
// per-state keyframe tracks and built-in behaviors (blink, lookAt, breathe, float), rendered
// from JSON so anyone — including an Agent — can design a character.
//
// Mirrors src/core/agents/avatar/renderer.js — keep the two in step.
package dev.supermux.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private const val XFADE = 300f

// ── Model ────────────────────────────────────────────────────────────────────────────────────

class AvatarRig(val size: Float, val parts: List<RigPart>, val states: Map<String, RigState>)

class RigPart(
    val id: String,
    val shape: String,
    val path: Path?,
    val nums: Map<String, Float>,
    val fill: RigPaint?,
    val stroke: Color?,
    val strokeWidth: Float,
    val lineCap: StrokeCap,
    val pivot: Offset?,
    /** `"pivot": "center"` — the part's own centre. */
    val pivotCenter: Boolean,
    /** Ignore the state's saturation (asleep keeps this part in colour). */
    val keepColor: Boolean,
    val behaviors: List<RigBehavior>,
    val children: List<RigPart>,
    val showIn: Set<String>?,
    val hideIn: Set<String>?,
) {
    fun n(key: String, default: Float) = nums[key] ?: default
}

sealed interface RigPaint {
    data class Solid(val color: Color) : RigPaint
    data class Linear(val from: Offset, val to: Offset, val stops: List<Pair<Float, Color>>) : RigPaint
    data class Radial(val center: Offset, val radius: Float, val stops: List<Pair<Float, Color>>) : RigPaint
}

class RigBehavior(val type: String, val opts: Map<String, Float>)

class RigTrack(
    val part: String,
    val prop: String,
    val keys: List<RigKey>,
    val duration: Float,
    val delay: Float,
    val loop: Boolean,
    val ease: String,
    /** `time` (default) or `level` — the live voice level picks the key position. */
    val driver: String = "time",
    /** `level`: a time track's motion is scaled by the voice level. */
    val gain: String? = null,
)

class RigKey(val t: Float, val value: JsonPrimitive, val ease: String?)

class RigState(val tracks: List<RigTrack>, val saturation: Float, val opacity: Float, val disable: Set<String>)

// ── Parsing ──────────────────────────────────────────────────────────────────────────────────

private val RigJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parse a v2 avatar; null when it isn't one (or isn't valid JSON). */
fun parseAvatarRig(text: String): AvatarRig? = runCatching {
    val root = RigJson.parseToJsonElement(text).jsonObject
    if (root.str("format") != "supermux-avatar") return null
    AvatarRig(
        size = root.num("size") ?: 100f,
        parts = root.arr("parts").map { parsePart(it.jsonObject) },
        states = root["states"]?.jsonObject?.mapValues { (_, v) -> parseState(v.jsonObject) }.orEmpty(),
    )
}.getOrNull()

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
private fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty()
private fun JsonElement.point(): Offset? = (this as? JsonArray)?.takeIf { it.size >= 2 }?.let {
    Offset(it[0].jsonPrimitive.floatOrNull ?: 0f, it[1].jsonPrimitive.floatOrNull ?: 0f)
}

private fun parsePart(o: JsonObject): RigPart {
    val d = o.str("d")
    val shape = o.str("shape") ?: if (d != null) "path" else "group"
    val nums = buildMap {
        for (k in listOf("cx", "cy", "r", "rx", "ry", "x0", "y0", "w", "h", "corner", "x", "y", "rotate", "scale", "scaleX", "scaleY", "opacity")) {
            o.num(k)?.let { put(k, it) }
        }
    }
    return RigPart(
        id = o.str("id").orEmpty(),
        shape = shape,
        path = if (shape == "path" && d != null) runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() else null,
        nums = nums,
        fill = if (o.containsKey("fill")) parsePaint(o["fill"]) else RigPaint.Solid(Color.Black),
        stroke = o.str("stroke")?.let { parseHexColor(it) },
        strokeWidth = o.num("strokeWidth") ?: 1f,
        lineCap = when (o.str("lineCap")) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
        pivot = o["pivot"]?.point(),
        pivotCenter = (o["pivot"] as? JsonPrimitive)?.content == "center",
        keepColor = (o["keepColor"] as? JsonPrimitive)?.booleanOrNull == true,
        behaviors = o.arr("behaviors").mapNotNull { b ->
            val bo = b as? JsonObject ?: return@mapNotNull (b as? JsonPrimitive)?.content?.let { RigBehavior(it, emptyMap()) }
            RigBehavior(bo.str("type").orEmpty(), bo.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.floatOrNull?.let { k to it } }.toMap())
        },
        children = o.arr("children").map { parsePart(it.jsonObject) },
        showIn = (o["showIn"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet(),
        hideIn = (o["hideIn"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet(),
    )
}

private fun parseStops(arr: List<JsonElement>): List<Pair<Float, Color>> = arr.mapIndexed { i, s ->
    if (s is JsonArray) (s[0].jsonPrimitive.floatOrNull ?: 0f) to parseHexColor(s[1].jsonPrimitive.content)
    else (if (arr.size > 1) i.toFloat() / (arr.size - 1) else 0f) to parseHexColor(s.jsonPrimitive.content)
}

private fun parsePaint(e: JsonElement?): RigPaint? = when (e) {
    null -> null
    is JsonPrimitive -> if (e.content == "none") null else RigPaint.Solid(parseHexColor(e.content))
    is JsonObject -> {
        e["linear"]?.jsonObject?.let { g ->
            RigPaint.Linear(g["from"]?.point() ?: Offset.Zero, g["to"]?.point() ?: Offset(0f, 100f), parseStops(g.arr("stops")))
        } ?: e["radial"]?.jsonObject?.let { g ->
            RigPaint.Radial(g["center"]?.point() ?: Offset(50f, 50f), g.num("radius") ?: 50f, parseStops(g.arr("stops")))
        }
    }
    else -> null
}

private fun parseState(o: JsonObject) = RigState(
    tracks = o.arr("tracks").mapNotNull { t ->
        val to = t as? JsonObject ?: return@mapNotNull null
        RigTrack(
            part = to.str("part").orEmpty(),
            prop = to.str("prop").orEmpty(),
            keys = to.arr("keys").mapNotNull { k ->
                (k as? JsonArray)?.takeIf { it.size >= 2 }?.let {
                    RigKey(it[0].jsonPrimitive.floatOrNull ?: 0f, it[1].jsonPrimitive, (it.getOrNull(2) as? JsonPrimitive)?.content)
                }
            },
            duration = (to.num("duration") ?: 1000f).coerceAtLeast(1f),
            delay = to.num("delay") ?: 0f,
            loop = (to["loop"] as? JsonPrimitive)?.booleanOrNull ?: true,
            ease = to.str("ease") ?: if (to.str("driver") == "level") "linear" else "inOut",
            driver = to.str("driver") ?: "time",
            gain = to.str("gain"),
        )
    },
    saturation = o.num("saturation") ?: 1f,
    opacity = o.num("opacity") ?: 1f,
    disable = o.arr("disable").map { it.jsonPrimitive.content }.toSet(),
)

// ── Evaluation ───────────────────────────────────────────────────────────────────────────────

private class V(
    var x: Float, var y: Float, var rotate: Float,
    var scale: Float, var scaleX: Float, var scaleY: Float,
    var opacity: Float, var fill: Color?, var vis: Float,
    var stroke: Color? = null, var strokeWidth: Float = 1f,
)

private fun ease(name: String, p: Float): Float = when (name) {
    "linear" -> p
    "in" -> p * p
    "out" -> 1 - (1 - p) * (1 - p)
    "step" -> if (p < 1f) 0f else 1f
    else -> if (p < 0.5f) 2 * p * p else 1 - (-2 * p + 2).pow(2) / 2
}

private fun AvatarRig.stateOf(name: String): RigState? = states[name] ?: states["idle"]

/** Hover is an overlay; "hover:<state>" overrides it for one state. */
private fun AvatarRig.hoverOf(name: String): RigState? = states["hover:$name"] ?: states["hover"]

/** Everything time-dependent one evaluation needs. */
private class RigCtx(val t: Float, val phase: Float, val level: Float, val pointer: Offset?, val hoverAt: Float)

private fun trackValue(tr: RigTrack, c: RigCtx, enteredAt: Float): JsonPrimitive? {
    val keys = tr.keys
    if (keys.isEmpty()) return null
    val p = if (tr.driver == "level") c.level.coerceIn(0f, 1f)
    else if (!tr.loop) ((c.t - enteredAt - tr.delay) / tr.duration).coerceIn(0f, 1f)
    else (((c.t + c.phase - tr.delay) % tr.duration) + tr.duration) % tr.duration / tr.duration
    if (p <= keys[0].t) return keys[0].value
    for (i in 0 until keys.size - 1) {
        val k0 = keys[i]
        val k1 = keys[i + 1]
        if (p in k0.t..k1.t) {
            val k = ease(k0.ease ?: tr.ease, if (k1.t > k0.t) (p - k0.t) / (k1.t - k0.t) else 1f)
            val a = k0.value.floatOrNull
            val b = k1.value.floatOrNull
            return if (a != null && b != null) JsonPrimitive(a + (b - a) * k)
            else JsonPrimitive(colorHex(lerp(parseHexColor(k0.value.content), parseHexColor(k1.value.content), k)))
        }
    }
    return keys.last().value
}

private fun colorHex(c: Color): String {
    fun h(f: Float) = (f * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + h(c.red) + h(c.green) + h(c.blue) + h(c.alpha)
}

private fun RigPart.hash(): Long = abs(id.hashCode().toLong())

private fun RigPart.baseValues() = V(
    n("x", 0f), n("y", 0f), n("rotate", 0f),
    n("scale", 1f), n("scaleX", 1f), n("scaleY", 1f),
    n("opacity", 1f), null, 1f,
)

private fun applyTracks(v: V, layer: RigState?, part: RigPart, c: RigCtx, enteredAt: Float) {
    for (tr in layer?.tracks.orEmpty()) {
        if (tr.part != part.id) continue
        val value = trackValue(tr, c, enteredAt) ?: continue
        var f = value.floatOrNull
        if (tr.gain == "level" && f != null) {
            val g = c.level.coerceIn(0f, 1f)
            f = if (tr.prop == "x" || tr.prop == "y" || tr.prop == "rotate") f * g else 1 + (f - 1) * g
        }
        when (tr.prop) {
            "x" -> v.x += f ?: 0f
            "y" -> v.y += f ?: 0f
            "rotate" -> v.rotate += f ?: 0f
            "scale" -> v.scale *= f ?: 1f
            "scaleX" -> v.scaleX *= f ?: 1f
            "scaleY" -> v.scaleY *= f ?: 1f
            "opacity" -> v.opacity *= f ?: 1f
            "strokeWidth" -> v.strokeWidth *= f ?: 1f
            "fill" -> v.fill = parseHexColor(value.content)
            "stroke" -> v.stroke = parseHexColor(value.content)
        }
    }
}

private fun applyBehaviors(v: V, part: RigPart, disabled: Set<String>, c: RigCtx) {
    val t = c.t + c.phase
    val h = part.hash()
    for (b in part.behaviors) {
        if (b.type in disabled) continue
        when (b.type) {
            "blink" -> {
                val every = (b.opts["every"] ?: 3500f) + (h % 1400) - 700
                val ph = ((t.toDouble() + h * 137.0) % every).toFloat()
                if (ph < 140f) v.scaleY *= 1 - 0.9f * sin(PI.toFloat() * ph / 140f)
            }
            "breathe" -> v.scale *= 1 + (b.opts["amount"] ?: 0.03f) * sin(2 * PI.toFloat() * t / (b.opts["period"] ?: 3200f))
            "float" -> v.y += (b.opts["amount"] ?: 2f) * sin(2 * PI.toFloat() * t / (b.opts["period"] ?: 2400f))
            "lookAt" -> {
                val r = b.opts["range"] ?: 3f
                val px = c.pointer?.x ?: (sin(t / 1700f) * 0.6f)
                val py = c.pointer?.y ?: (sin(t / 2300f) * 0.3f)
                v.x += px.coerceIn(-1f, 1f) * r
                v.y += py.coerceIn(-1f, 1f) * r
            }
        }
    }
}

private fun RigPart.visibleIn(state: String, hovered: Boolean): Float {
    fun inSet(s: Set<String>?) = s != null && (state in s || (hovered && "hover" in s))
    if (showIn != null && !inSet(showIn)) return 0f
    if (hideIn != null && inSet(hideIn)) return 0f
    return 1f
}

/** One part's animated values in one state, with the hover overlay mixed in at [hoverAmt]. */
private fun AvatarRig.evalPart(part: RigPart, state: String, hoverAmt: Float, c: RigCtx, enteredAt: Float): V {
    val st = stateOf(state)
    val plain = part.baseValues()
    applyTracks(plain, st, part, c, enteredAt)
    applyBehaviors(plain, part, st?.disable.orEmpty(), c)
    plain.vis = part.visibleIn(state, false)
    if (hoverAmt <= 0f) return plain
    val hov = hoverOf(state)
    val over = part.baseValues()
    applyTracks(over, st, part, c, enteredAt)
    applyTracks(over, hov, part, c, c.hoverAt)
    applyBehaviors(over, part, st?.disable.orEmpty() + hov?.disable.orEmpty(), c)
    over.vis = part.visibleIn(state, true)
    return if (hoverAmt >= 1f) over else mix(plain, over, hoverAmt)
}

private fun mixColor(a: Color?, b: Color?, k: Float) =
    if (a != null && b != null) lerp(a, b, k) else if (k < 0.5f) a else b

private fun mix(a: V, b: V, k: Float): V {
    val turn = ((((b.rotate - a.rotate) % 360f) + 540f) % 360f) - 180f // the short way round
    return V(
        a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.rotate + turn * k,
        a.scale + (b.scale - a.scale) * k, a.scaleX + (b.scaleX - a.scaleX) * k, a.scaleY + (b.scaleY - a.scaleY) * k,
        a.opacity + (b.opacity - a.opacity) * k, mixColor(a.fill, b.fill, k), a.vis + (b.vis - a.vis) * k,
        mixColor(a.stroke, b.stroke, k), a.strokeWidth + (b.strokeWidth - a.strokeWidth) * k,
    )
}

// ── Rendering ────────────────────────────────────────────────────────────────────────────────

private class RigClock { var state = ""; var prev = ""; var switchedAt = 0f; var hovered = false; var hoverAt = 0f }

@Composable
fun AvatarRigView(
    rig: AvatarRig,
    state: String,
    hovered: Boolean = false,
    /** Pointer relative to the avatar, each axis −1..1; null lets lookAt parts drift. */
    pointer: Offset? = null,
    /** Live voice level 0..1 (listening: the user's mic; speaking: the Agent's voice). */
    level: Float = 0f,
    /** Per-Agent seed (its id) so two Agents with the same avatar don't move in lockstep. */
    seed: String = "",
    size: Int = 34,
    /** Fixed time (ms) instead of the frame clock — for contact sheets. */
    fixedTime: Long? = null,
) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fixedTime) {
        if (fixedTime != null) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now = it - start }
    }
    val t = (fixedTime ?: now).toFloat()
    val clock = remember { RigClock() }
    if (clock.state != state) {
        clock.prev = clock.state.ifEmpty { state }
        clock.state = state
        clock.switchedAt = t
    }
    if (clock.hovered != hovered) {
        clock.hovered = hovered
        clock.hoverAt = t
    }
    val phase = remember(seed) { if (seed.isEmpty()) 0f else (abs(seed.hashCode()) % 10007).toFloat() }
    Canvas(Modifier.size(size.dp)) {
        val k = if (clock.prev != state) ((t - clock.switchedAt) / XFADE).coerceIn(0f, 1f) else 1f
        // Hover cross-fades like a state change.
        val hk = ((t - clock.hoverAt) / XFADE).coerceIn(0f, 1f)
        val hoverAmt = ease("inOut", if (hovered) hk else 1f - hk)
        val c = RigCtx(t, phase, level, pointer, clock.hoverAt)
        val st = rig.stateOf(state)
        val pst = rig.stateOf(clock.prev)
        val sat = (pst?.saturation ?: 1f) + ((st?.saturation ?: 1f) - (pst?.saturation ?: 1f)) * k
        val alpha = (pst?.opacity ?: 1f) + ((st?.opacity ?: 1f) - (pst?.opacity ?: 1f)) * k
        val filter = if (sat < 0.999f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(sat) }) else null
        val unit = this.size.width / rig.size
        clipRect {
            scale(unit, unit, pivot = Offset.Zero) {
                for (p in rig.parts) drawPart(rig, p, state, clock.prev, k, hoverAmt, c, clock.switchedAt, alpha, filter)
            }
        }
    }
}

private fun RigPart.ownCenter(): Offset? = when (shape) {
    "circle", "ellipse" -> Offset(n("cx", 50f), n("cy", 50f))
    "rect" -> Offset(n("x0", 0f) + n("w", 10f) / 2, n("y0", 0f) + n("h", 10f) / 2)
    "path" -> path?.getBounds()?.center
    else -> children.mapNotNull { it.ownCenter() }.takeIf { it.isNotEmpty() }?.let { cs ->
        Offset(cs.sumOf { it.x.toDouble() }.toFloat() / cs.size, cs.sumOf { it.y.toDouble() }.toFloat() / cs.size)
    }
}

private fun DrawScope.drawPart(
    rig: AvatarRig, part: RigPart, state: String, prev: String, k: Float, hoverAmt: Float,
    c: RigCtx, switchedAt: Float, parentAlpha: Float, filter: ColorFilter?,
) {
    var v = rig.evalPart(part, state, hoverAmt, c, switchedAt)
    if (k < 1f) v = mix(rig.evalPart(part, prev, hoverAmt, c, 0f), v, ease("inOut", k))
    val alpha = (parentAlpha * v.opacity * v.vis).coerceIn(0f, 1f)
    if (alpha <= 0.001f) return
    val pivot = (if (part.pivotCenter) part.ownCenter() else part.pivot) ?: Offset(rig.size / 2, rig.size / 2)
    val f = if (part.keepColor) null else filter
    withTransform({
        translate(v.x, v.y)
        rotate(v.rotate, pivot)
        scale(v.scale * v.scaleX, v.scale * v.scaleY, pivot)
    }) {
        val fill = v.fill?.let { RigPaint.Solid(it) } ?: part.fill
        fill?.let { drawShape(part, brushOf(it), Fill, alpha, f) }
        (v.stroke ?: part.stroke)?.let {
            drawShape(part, SolidColor(it), Stroke(width = part.strokeWidth * v.strokeWidth, cap = part.lineCap, join = StrokeJoin.Round), alpha, f)
        }
        for (ch in part.children) drawPart(rig, ch, state, prev, k, hoverAmt, c, switchedAt, alpha, filter)
    }
}

private fun brushOf(p: RigPaint): Brush = when (p) {
    is RigPaint.Solid -> SolidColor(p.color)
    is RigPaint.Linear -> if (p.stops.size < 2) SolidColor(p.stops.firstOrNull()?.second ?: Color.Black)
    else Brush.linearGradient(*p.stops.toTypedArray(), start = p.from, end = p.to)
    is RigPaint.Radial -> if (p.stops.size < 2) SolidColor(p.stops.firstOrNull()?.second ?: Color.Black)
    else Brush.radialGradient(*p.stops.toTypedArray(), center = p.center, radius = p.radius)
}

private fun DrawScope.drawShape(part: RigPart, brush: Brush, style: DrawStyle, alpha: Float, filter: ColorFilter?) {
    when (part.shape) {
        "path" -> part.path?.let { drawPath(it, brush, alpha, style, filter) }
        "circle" -> {
            val r = part.n("r", 10f)
            drawCircle(brush, r, Offset(part.n("cx", 50f), part.n("cy", 50f)), alpha, style, filter)
        }
        "ellipse" -> {
            val rx = part.n("rx", 10f); val ry = part.n("ry", 10f)
            drawOval(brush, Offset(part.n("cx", 50f) - rx, part.n("cy", 50f) - ry), Size(rx * 2, ry * 2), alpha, style, filter)
        }
        "rect" -> {
            val c = part.n("corner", 0f)
            drawRoundRect(brush, Offset(part.n("x0", 0f), part.n("y0", 0f)), Size(part.n("w", 10f), part.n("h", 10f)), CornerRadius(c, c), alpha, style, filter)
        }
    }
}
