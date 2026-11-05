// An Agent's avatar wearing round glasses: what the assistant overlay shows while it can see your screen
// (#62). Drawn into the avatar itself, so they move with its head.
package dev.supermux.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.max
import kotlin.math.min

private val GLASSES_INK = Color(0xFF2B2F3A)
/** A light edge under the frame, so it reads on a dark face as on a light one. */
private val GLASSES_HALO = Color(0x8CFFFFFF)
private val LENS_TINT = Color(0x33CFEAFF)

/**
 * This avatar wearing round glasses: a pair around its eyes, put in the part that holds its `eyes`, so
 * they move with its head but don't blink. Null when there aren't two eyes to find (an `eyes` part with
 * two round, rectangular or path eyes in it); the host shows a badge instead. The avatar itself is left
 * as it was.
 */
fun AvatarRig.wearingGlasses(): AvatarRig? {
    val route = routeTo(parts, "eyes") ?: return null
    val eyes = route.last()
    val shift = Offset(eyes.n("x", 0f), eyes.n("y", 0f))
    val found = eyes.children.mapNotNull { eye -> eye.bounds()?.translate(shift + Offset(eye.n("x", 0f), eye.n("y", 0f))) }
    if (found.size < 2) return null
    val left = found.minBy { it.center.x }
    val right = found.maxBy { it.center.x }
    if (right.center.x - left.center.x <= 0f) return null
    val glasses = glassesFor(left, right)
    val holder = route.getOrNull(route.size - 2) ?: return AvatarRig(size, parts + glasses, states)
    val ancestors = route.dropLast(1)
    fun rebuild(list: List<RigPart>): List<RigPart> = list.map { p ->
        when {
            p === holder -> p.withChildren(rebuild(p.children) + glasses)
            ancestors.any { it === p } -> p.withChildren(rebuild(p.children))
            else -> p
        }
    }
    return AvatarRig(size, rebuild(parts), states)
}

/** The parts from the top down to the first part called [id], or null. */
private fun routeTo(parts: List<RigPart>, id: String): List<RigPart>? {
    for (p in parts) {
        if (p.id.equals(id, ignoreCase = true)) return listOf(p)
        routeTo(p.children, id)?.let { return listOf(p) + it }
    }
    return null
}

/** Where one eye is: a round or square eye itself; a group by its biggest round part (an eye's white). */
private fun RigPart.bounds(): Rect? = when (shape) {
    "ellipse" -> Rect(n("cx", 50f) - n("rx", 10f), n("cy", 50f) - n("ry", 10f), n("cx", 50f) + n("rx", 10f), n("cy", 50f) + n("ry", 10f))
    "circle" -> Rect(Offset(n("cx", 50f), n("cy", 50f)), n("r", 10f))
    "rect" -> Rect(n("x0", 0f), n("y0", 0f), n("x0", 0f) + n("w", 10f), n("y0", 0f) + n("h", 10f))
    "path" -> path?.getBounds()?.takeIf { it.width > 0f && it.height > 0f }
    else -> {
        val inside = children.mapNotNull { c -> c.bounds()?.let { c to it.translate(c.n("x", 0f), c.n("y", 0f)) } }
        inside.filter { (c, _) -> c.shape == "ellipse" || c.shape == "circle" }.maxByOrNull { (_, r) -> r.width * r.height }?.second
            ?: inside.map { it.second }.reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }
}

private fun glassesFor(left: Rect, right: Rect): RigPart {
    fun pad(e: Rect) = max(e.width, e.height) / 2 * 0.38f
    var lx = left.width / 2 + pad(left)
    var rx = right.width / 2 + pad(right)
    val ly = left.height / 2 + pad(left) * 0.8f
    val ry = right.height / 2 + pad(right) * 0.8f
    // Two lenses, never touching: a gap for the bridge.
    val apart = right.center.x - left.center.x
    val room = apart * 0.92f
    if (lx + rx > room) {
        val k = room / (lx + rx)
        lx *= k; rx *= k
    }
    val l = left.center
    val r = right.center
    val y = (l.y + r.y) / 2
    val ink = (max(lx, ly) * 0.26f).coerceIn(1.1f, 2.8f)
    val bridge = "M${l.x + lx} ${y - ly * 0.15f} Q${(l.x + r.x) / 2} ${y - ly * 0.55f} ${r.x - rx} ${y - ry * 0.15f}"
    val temples = "M${l.x - lx} ${y - ly * 0.1f} L${l.x - lx * 1.45f} ${y - ly * 0.35f} M${r.x + rx} ${y - ry * 0.1f} L${r.x + rx * 1.45f} ${y - ry * 0.35f}"
    val glints = "M${l.x - lx * 0.45f} ${l.y + ly * 0.05f} L${l.x - lx * 0.1f} ${l.y - ly * 0.45f} " +
        "M${r.x - rx * 0.45f} ${r.y + ry * 0.05f} L${r.x - rx * 0.1f} ${r.y - ry * 0.45f}"
    return group(
        "glasses",
        ring("haloL", l, lx, ly, null, GLASSES_HALO, ink * 1.9f),
        ring("haloR", r, rx, ry, null, GLASSES_HALO, ink * 1.9f),
        line("frameHalo", "$bridge $temples", GLASSES_HALO, ink * 1.9f),
        ring("lensL", l, lx, ly, LENS_TINT, GLASSES_INK, ink),
        ring("lensR", r, rx, ry, LENS_TINT, GLASSES_INK, ink),
        line("frame", "$bridge $temples", GLASSES_INK, ink),
        line("glint", glints, Color.White, ink * 0.75f),
    )
}

private fun part(
    id: String, shape: String, nums: Map<String, Float> = emptyMap(), d: String? = null, fill: Color? = null,
    stroke: Color? = null, width: Float = 1f, children: List<RigPart> = emptyList(),
) = RigPart(
    id = id,
    shape = shape,
    path = d?.let { PathParser().parsePathString(it).toPath() },
    nums = nums,
    fill = fill?.let { RigPaint.Solid(it) },
    stroke = stroke,
    strokeWidth = width,
    lineCap = StrokeCap.Round,
    pivot = null,
    pivotCenter = false,
    keepColor = false,
    behaviors = emptyList(),
    children = children,
    showIn = null,
    hideIn = null,
)

private fun group(id: String, vararg children: RigPart) = part(id, "group", children = children.toList())

private fun ring(id: String, c: Offset, rx: Float, ry: Float, fill: Color?, stroke: Color, width: Float) =
    part(id, "ellipse", mapOf("cx" to c.x, "cy" to c.y, "rx" to rx, "ry" to ry), fill = fill, stroke = stroke, width = width)

private fun line(id: String, d: String, stroke: Color, width: Float) = part(id, "path", d = d, stroke = stroke, width = width)

private fun RigPart.withChildren(children: List<RigPart>) = RigPart(
    id, shape, path, nums, fill, stroke, strokeWidth, lineCap, pivot, pivotCenter, keepColor, behaviors, children, showIn, hideIn,
)
