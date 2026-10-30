// Animated Agent avatars, rendered from an [AgentAvatarSpec] (JSON). A small living character
// per Agent, in the spirit of OpenAI's Dots and Codex Pets: it breathes while idle, blinks,
// wobbles and glances around while working, hops when it has something for you, perks up on
// hover and sleeps when its session is off. What each state looks like comes from the spec.
package dev.supermux.ui.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2 * PI).toFloat()

@Composable
internal fun AgentAvatar(
    name: String,
    online: Boolean,
    working: Boolean,
    unread: Boolean = false,
    hovered: Boolean = false,
    size: Int = 34,
    spec: AgentAvatarSpec? = null,
) {
    val look = spec ?: remember(name) { defaultAgentAvatar(name) }
    val state = when {
        !online -> look.states.asleep
        working -> look.states.working
        unread -> look.states.waiting
        else -> look.states.idle
    }
    val idle = look.states.idle
    val speed = state.speed ?: idle.speed ?: 3600
    val t = rememberInfiniteTransition(label = "agent-avatar")
    val clock by t.animateFloat(0f, TAU, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "clock")
    val bounce by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bounce",
    )
    val perk by animateFloatAsState(if (hovered && online) 1f else 0f, spring(dampingRatio = 0.45f), label = "perk")
    val dim by animateFloatAsState(if (state.desaturate) 1f else 0f, tween(500), label = "dim")

    val blink = remember { Animatable(1f) }
    LaunchedEffect(name, look.eyes.blink) {
        if (!look.eyes.blink) return@LaunchedEffect
        val rnd = Random(name.hashCode())
        while (true) {
            delay(1800L + rnd.nextLong(3200))
            blink.animateTo(0.08f, tween(70)); blink.animateTo(1f, tween(110))
            if (rnd.nextInt(5) == 0) {
                delay(120); blink.animateTo(0.08f, tween(70)); blink.animateTo(1f, tween(110))
            }
        }
    }

    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val cx = w / 2
        val motion = state.motion
        val hop = if (motion == "hop") -bounce * w * 0.07f else 0f
        val shake = if (motion == "shake") sin(clock * 6) * w * 0.035f else 0f
        val tilt = if (motion == "spin") sin(clock) * 14f else 0f
        val breathe = 1f + (state.breathe ?: idle.breathe ?: 0.035f) * sin(clock)
        val r = w * 0.36f * breathe * (1f + 0.06f * perk)
        val cy = w * 0.54f + hop + dim * w * 0.03f
        val wobble = state.wobble ?: look.body.wobble

        val shadowScale = 1f - (-hop / (w * 0.07f)).coerceIn(0f, 1f) * 0.3f
        drawOval(
            Color.Black.copy(alpha = 0.22f),
            topLeft = Offset(cx - r * 0.7f * shadowScale, w * 0.91f),
            size = Size(r * 1.4f * shadowScale, w * 0.07f),
        )

        val gray = Color(0xFF8A8A8E)
        fun c(hex: String?, fb: Color = Color.Gray) = parseHexColor(hex, fb).let { lerp(it, lerp(gray, it, 0.25f), dim) }
        val colors = look.body.colors.ifEmpty { listOf("#9B87FF") }.map { c(it) }

        translate(left = shake) {
            rotate(tilt, pivot = Offset(cx, cy)) {
                val body = bodyPath(look.body.shape, cx, cy, r, wobble, look.body.lobes, clock)
                look.accessory?.let { drawAccessory(it.type, c(it.color, Color.White), cx, cy, r, clock, behind = true) }
                drawPath(
                    body,
                    if (colors.size == 1) Brush.verticalGradient(listOf(colors[0], colors[0]))
                    else Brush.verticalGradient(colors, startY = cy - r, endY = cy + r),
                )
                look.body.outline?.let { drawPath(body, c(it), style = Stroke(width = r * 0.08f)) }
                if (look.body.gloss) {
                    drawOval(
                        Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(cx - r * 0.55f, cy - r * 0.78f),
                        size = Size(r * 0.62f, r * 0.34f),
                    )
                }
                look.accessory?.let { drawAccessory(it.type, c(it.color, Color.White), cx, cy, r, clock, behind = false) }
                drawFace(look, state, cx, cy, r, clock, perk, blink.value, ::c)
                state.badge?.let { drawBadge(it, cx + r * 0.85f, cy - r * 1.05f + sin(clock) * r * 0.06f, r * 0.28f) }
            }
        }
    }
}

private fun bodyPath(shape: String, cx: Float, cy: Float, r: Float, wobble: Float, lobes: Int, clock: Float): Path {
    val path = Path()
    val steps = 64
    for (i in 0..steps) {
        val a = i.toFloat() / steps * TAU
        val ripple = 1f + wobble * sin(lobes * a + clock * 2) + wobble * 0.5f * cos(2 * a - clock)
        val ca = cos(a); val sa = sin(a)
        var x: Float; var y: Float
        when (shape) {
            "squircle" -> {
                val n = 4f
                x = cx + r * 1.02f * ripple * sign(ca) * abs(ca).pow(2f / n)
                y = cy + r * ripple * sign(sa) * abs(sa).pow(2f / n)
            }
            "ghost" -> {
                x = cx + r * ripple * ca
                y = if (sa > 0) cy + r * (0.75f + 0.12f * sin(ca * 9f + clock * 3)) else cy + r * ripple * sa
            }
            "circle" -> {
                val rr = r * (1f + (ripple - 1f) * 0.3f)
                x = cx + rr * ca; y = cy + rr * sa
            }
            else -> { // blob
                val rr = r * ripple
                x = cx + rr * ca; y = cy + rr * sa * (if (sa > 0) 0.92f else 1f)
            }
        }
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun DrawScope.drawFace(
    look: AgentAvatarSpec, state: AgentAvatarSpec.State,
    cx: Float, cy: Float, r: Float, clock: Float, perk: Float, blink: Float,
    c: (String?, Color) -> Color,
) {
    val ink = c(look.eyes.color, Color(0xFF1B1530))
    val mouthInk = c(look.mouth.color ?: look.eyes.color, Color(0xFF1B1530))
    val gap = r * look.eyes.spacing
    val eyeMode = state.eyes
    val lookX = if (eyeMode == "glance") sin(clock) * r * 0.12f else 0f
    val lookY = if (eyeMode == "up") -r * 0.10f else -r * 0.06f * perk
    val ey = cy - r * 0.05f + lookY
    val s = look.eyes.size
    val style = when (eyeMode) {
        null, "glance", "up" -> look.eyes.style
        else -> eyeMode
    }
    val stroke = Stroke(width = r * 0.09f, cap = StrokeCap.Round)
    when (style) {
        "closed" -> for (dx in listOf(-gap, gap)) {
            drawPath(Path().apply {
                moveTo(cx + dx - r * 0.15f, ey + r * 0.02f)
                quadraticTo(cx + dx, ey + r * 0.16f, cx + dx + r * 0.15f, ey + r * 0.02f)
            }, ink.copy(alpha = 0.8f), style = stroke)
        }
        "happy" -> for (dx in listOf(-gap, gap)) {
            drawPath(Path().apply {
                moveTo(cx + dx + lookX - r * 0.14f * s, ey + r * 0.06f)
                quadraticTo(cx + dx + lookX, ey - r * 0.14f * s, cx + dx + lookX + r * 0.14f * s, ey + r * 0.06f)
            }, ink, style = stroke)
        }
        "visor" -> {
            val vw = gap * 2 + r * 0.4f
            val vh = r * 0.22f * s * blink.coerceAtLeast(0.25f)
            drawRoundRect(ink, Offset(cx - vw / 2, ey - vh / 2), Size(vw, vh), CornerRadius(vh / 2))
            val scan = cx + lookX * 2 + sin(clock) * vw * 0.3f
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(scan - r * 0.08f, ey - vh / 2), Size(r * 0.16f, vh), CornerRadius(vh / 2))
        }
        "sparkle" -> for (dx in listOf(-gap, gap)) {
            val ex = cx + dx + lookX
            val k = r * 0.17f * s * (0.85f + 0.15f * sin(clock * 2)) * blink.coerceAtLeast(0.2f)
            drawPath(Path().apply {
                moveTo(ex, ey - k); quadraticTo(ex, ey, ex + k, ey); quadraticTo(ex, ey, ex, ey + k)
                quadraticTo(ex, ey, ex - k, ey); quadraticTo(ex, ey, ex, ey - k); close()
            }, ink)
        }
        "dot" -> for (dx in listOf(-gap, gap)) {
            val rad = r * 0.09f * s
            drawOval(ink, Offset(cx + dx + lookX - rad, ey - rad * blink), Size(rad * 2, (rad * 2 * blink).coerceAtLeast(r * 0.04f)))
        }
        else -> { // pill
            val ew = r * 0.17f * s
            val eh = r * 0.30f * s * (1f + 0.25f * perk) * blink
            for (dx in listOf(-gap, gap)) {
                drawRoundRect(ink, Offset(cx + dx + lookX - ew / 2, ey - eh / 2), Size(ew, eh.coerceAtLeast(r * 0.04f)), CornerRadius(ew / 2))
                if (blink > 0.6f) {
                    drawCircle(Color.White.copy(alpha = 0.9f), ew * 0.22f, Offset(cx + dx + lookX + ew * 0.12f, ey - eh * 0.22f))
                }
            }
        }
    }

    val my = cy + r * 0.32f
    when (state.mouth ?: look.mouth.style) {
        "none" -> {}
        "o" -> drawCircle(mouthInk, r * (0.07f + 0.03f * (0.5f + 0.5f * sin(clock * 2))), Offset(cx + lookX * 0.5f, my))
        "line" -> drawLine(mouthInk, Offset(cx - r * 0.14f, my), Offset(cx + r * 0.14f, my), r * 0.08f, StrokeCap.Round)
        "cat" -> drawPath(Path().apply {
            val k = r * 0.1f
            moveTo(cx - 2 * k, my - k * 0.3f); quadraticTo(cx - k, my + k, cx, my - k * 0.2f)
            quadraticTo(cx + k, my + k, cx + 2 * k, my - k * 0.3f)
        }, mouthInk, style = Stroke(width = r * 0.07f, cap = StrokeCap.Round))
        else -> { // smile / grin
            val wide = r * (0.16f + 0.08f * perk + if (state.mouth == "grin") 0.06f else 0f)
            drawPath(Path().apply {
                moveTo(cx - wide, my - r * 0.02f)
                quadraticTo(cx, my + r * (0.14f + 0.06f * perk), cx + wide, my - r * 0.02f)
            }, mouthInk, style = Stroke(width = r * 0.08f, cap = StrokeCap.Round))
        }
    }
    look.cheeks?.let {
        val blush = c(it.color, Color(0xFFFF6B8A)).copy(alpha = 0.35f + 0.25f * perk)
        drawCircle(blush, r * 0.11f, Offset(cx - gap * 1.45f, my - r * 0.06f))
        drawCircle(blush, r * 0.11f, Offset(cx + gap * 1.45f, my - r * 0.06f))
    }
}

private fun DrawScope.drawAccessory(type: String, color: Color, cx: Float, cy: Float, r: Float, clock: Float, behind: Boolean) {
    val top = cy - r
    when (type) {
        "antenna" -> if (!behind) {
            val tipX = cx + sin(clock) * r * 0.12f
            drawLine(color, Offset(cx, top + r * 0.05f), Offset(tipX, top - r * 0.38f), r * 0.07f, StrokeCap.Round)
            drawCircle(color.copy(alpha = 0.35f + 0.35f * (0.5f + 0.5f * sin(clock * 3))), r * 0.2f, Offset(tipX, top - r * 0.42f))
            drawCircle(color, r * 0.1f, Offset(tipX, top - r * 0.42f))
        }
        "ears" -> if (behind) for (d in listOf(-1f, 1f)) {
            val ex = cx + d * r * 0.55f
            drawPath(Path().apply {
                moveTo(ex - r * 0.3f, top + r * 0.35f)
                lineTo(ex + d * r * 0.1f, top - r * 0.35f + sin(clock + d) * r * 0.04f)
                lineTo(ex + r * 0.3f, top + r * 0.35f); close()
            }, color)
        }
        "horns" -> if (behind) for (d in listOf(-1f, 1f)) {
            val ex = cx + d * r * 0.5f
            drawPath(Path().apply {
                moveTo(ex - r * 0.15f, top + r * 0.3f)
                quadraticTo(ex + d * r * 0.05f, top - r * 0.1f, ex + d * r * 0.3f, top - r * 0.3f)
                lineTo(ex + r * 0.15f, top + r * 0.3f); close()
            }, color)
        }
        "leaf" -> if (!behind) rotate(sin(clock) * 18f, pivot = Offset(cx, top + r * 0.05f)) {
            drawLine(color, Offset(cx, top + r * 0.08f), Offset(cx, top - r * 0.18f), r * 0.06f, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(cx, top - r * 0.15f)
                quadraticTo(cx + r * 0.4f, top - r * 0.45f, cx + r * 0.5f, top - r * 0.2f)
                quadraticTo(cx + r * 0.2f, top - r * 0.02f, cx, top - r * 0.15f); close()
            }, color)
        }
        "halo" -> if (!behind) {
            val hy = top - r * 0.22f + sin(clock) * r * 0.05f
            drawOval(color, Offset(cx - r * 0.5f, hy - r * 0.11f), Size(r, r * 0.22f), style = Stroke(width = r * 0.08f))
        }
    }
}

private fun DrawScope.drawBadge(badge: String, x: Float, y: Float, s: Float) {
    val ink = Color.White.copy(alpha = 0.8f)
    val st = Stroke(width = s * 0.28f, cap = StrokeCap.Round)
    when (badge) {
        "z" -> drawPath(Path().apply { moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s) }, ink, style = st)
        "!" -> {
            val a = Color(0xFFFFC56B)
            drawLine(a, Offset(x + s / 2, y - s * 0.2f), Offset(x + s / 2, y + s * 0.6f), s * 0.3f, StrokeCap.Round)
            drawCircle(a, s * 0.16f, Offset(x + s / 2, y + s * 1.0f))
        }
        else -> drawCircle(ink, s * 0.25f, Offset(x + s / 2, y + s / 2))
    }
}
