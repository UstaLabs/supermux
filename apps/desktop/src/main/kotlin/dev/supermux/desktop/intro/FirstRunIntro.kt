// First-run cinematic ("mux boot"), shown ONCE per install (IntroStateStore) the first time the
// desktop app opens. One linear timeline `t ∈ 0..1` over 4.8s drives every layer; each element
// maps its own sub-segment of t, so the whole piece is deterministic (and freezable for
// headless screenshots via SM_INTRO_FREEZE=<t>):
//
//   0.03–0.11  boot: "$ supermux" types itself, block cursor
//   0.12–0.26  boot log: ":: secure channel … ok" lines stream in
//   0.27–0.44  the terminal "splits": hairline sweeps cut a 2×2 tmux-style pane grid
//   0.42–0.62  four agent panes come alive (claude/codex/opencode/cursor), logs scrolling
//   0.58–0.76  converge: the grid collapses centerward into a 90-particle stream
//   0.68–0.90  bloom: radial teal glow + the REAL logo mark draws itself (stroke trim cascade),
//              fills, one diagonal scanline sweep passes over it
//   0.86–0.95  "supermux" wordmark + "AFK. Still shipping." tagline rise in
//   0.95–1.00  handoff: overlay fades, revealing the already-composed app beneath
//
// Click / any key skips (quick fade). Everything renders in one Box: a full-screen Canvas for
// background/grid/particles/logo and two small Compose text layers (boot log, wordmark). The
// overlay is emitted as the LAST child of SupermuxTheme in Main.kt — desktop Window content
// stacks siblings Box-style, so it covers the app without a wrapper.
package dev.supermux.desktop.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.GeistFontFamily
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val DURATION_MS = 4_800

// Cinematic fixed palette (the intro is ALWAYS dark, independent of AppearanceMode); the brand
// teal + status colors come from LocalSemantics so the piece tracks the design-language palette.
private val Bg = Color(0xFF060A0B)
private val PaneBg = Color(0xFF0A1112)
private val Ink = Color(0xFFD3E2DD)
private val Dim = Color(0xFF5B7069)
private val MarkLight = Color(0xFFA8F5E1)
private val WordmarkInk = Color(0xFFE9F2EF)
private val TaglineDim = Color(0xFF6E857D)

private fun seg(t: Float, a: Float, b: Float): Float = if (t <= a) 0f else if (t >= b) 1f else (t - a) / (b - a)
private fun easeOutCubic(p: Float): Float { val u = 1f - p; return 1f - u * u * u }
private fun easeInCubic(p: Float): Float = p * p * p
private fun easeInOutCubic(p: Float): Float = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it / 2f }
private val EaseOutBack = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

private fun quadBezier(s: Offset, c: Offset, e: Offset, p: Float): Offset {
    val u = 1f - p
    return Offset(u * u * s.x + 2f * u * p * c.x + p * p * e.x, u * u * s.y + 2f * u * p * c.y + p * p * e.y)
}

// Pseudo agent activity scrolling in the mux panes. ASCII-only (no glyph-coverage risk in any
// mono font); rows starting with "ok" render in the success color, the rest in Dim.
private val ROW_STRINGS = listOf(
    "> reading src/auth/middleware.ts",
    "> grep -rn 'TODO' src/",
    "ok build passed (14 modules)",
    "> editing apps/desktop/Main.kt",
    "> vitest --watch .. 42 passed",
    "ok lint clean",
    "> git diff --stat  +128 -41",
    "> planning: split session pane",
    "> curl :9898/sessions",
    "> writing docs/specs/mux.md",
    "ok review requested",
    "> tail -f broker.log",
)

private val PANE_HEADERS = listOf(
    "claude :: api-refactor",
    "codex :: tests",
    "opencode :: docs",
    "cursor :: ui-polish",
)

// Deterministic particle seeds (fixed Random seed — the choreography must not change per run).
// sx/sy: start point as a fraction of the grid size around the grid center; arc: signed
// perpendicular bulge of the flight path (fraction of grid width); delay: stagger within the
// converge window; colorIdx indexes [brand, MarkLight, success, white].
private data class ParticleSeed(
    val sx: Float, val sy: Float, val arc: Float,
    val delay: Float, val sizeDp: Float, val colorIdx: Int,
)

private val PARTICLES: List<ParticleSeed> = run {
    val rnd = kotlin.random.Random(7)
    List(90) {
        ParticleSeed(
            sx = rnd.nextFloat() - 0.5f,
            sy = (rnd.nextFloat() - 0.5f) * 0.75f,
            arc = rnd.nextFloat() - 0.5f,
            delay = rnd.nextFloat() * 0.06f,
            sizeDp = 1.6f + rnd.nextFloat() * 2.4f,
            colorIdx = rnd.nextInt(4),
        )
    }
}

@Composable
fun FirstRunIntroOverlay(onFinished: () -> Unit) {
    val semantics = LocalSemantics.current
    val brand = semantics.brand
    val success = semantics.success
    val particleColors = listOf(brand, MarkLight, success, WordmarkInk)

    val scope = rememberCoroutineScope()
    val t = remember { Animatable(0f) }
    val skipAlpha = remember { Animatable(1f) }
    var finished by remember { mutableStateOf(false) }
    // Headless-screenshot hook: freeze the timeline at a fixed t (no auto-advance, no auto-finish).
    val freeze = remember { System.getenv("SM_INTRO_FREEZE")?.toFloatOrNull()?.coerceIn(0f, 0.999f) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        if (freeze != null) {
            t.snapTo(freeze)
        } else {
            t.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
            if (!finished) {
                finished = true
                onFinished()
            }
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        scope.launch {
            skipAlpha.animateTo(0f, tween(260, easing = LinearEasing))
            onFinished()
        }
    }

    // Hard-blinking block cursor (threshold below), independent of the main timeline.
    val blink by rememberInfiniteTransition().animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Restart),
    )
    val cursorOn = blink < 0.5f

    val measurer = rememberTextMeasurer()
    val rowStyle = TextStyle(fontFamily = MonoFontFamily, fontSize = 10.sp)
    val headerStyle = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
    val rowLayouts = remember { ROW_STRINGS.map { measurer.measure(it, rowStyle) } }
    val headerLayouts = remember { PANE_HEADERS.map { measurer.measure(it, headerStyle) } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) { finish(); true } else false }
            .pointerInput(Unit) { detectTapGestures { finish() } }
            .graphicsLayer { alpha = (1f - seg(t.value, 0.95f, 1f)) * skipAlpha.value },
    ) {
        val headerColors = listOf(brand, success, semantics.info, semantics.warning)

        Canvas(Modifier.fillMaxSize()) {
            val tt = t.value
            val gridW = min(size.width * 0.60f, 780.dp.toPx())
            val gridH = min(size.height * 0.46f, 430.dp.toPx())
            val gridC = Offset(size.width / 2f, size.height / 2f)
            val logoC = Offset(size.width / 2f, size.height * 0.44f)
            val logoSize = 210.dp.toPx()

            // --- background: near-black + a faint brand vignette that swells during the bloom ---
            drawRect(Bg)
            val vigA = 0.05f + 0.10f * seg(tt, 0.68f, 0.86f)
            val vigR = maxOf(size.width, size.height) * 0.75f
            drawCircle(
                Brush.radialGradient(listOf(brand.copy(alpha = vigA), Color.Transparent), logoC, vigR),
                radius = vigR, center = logoC,
            )

            // --- mux grid (2×2 agent panes), collapsing toward the logo during converge ---------
            val convP = easeInCubic(seg(tt, 0.58f, 0.70f))
            val gAlpha = 1f - convP
            if (tt > 0.26f && gAlpha > 0f) {
                val center = Offset(
                    gridC.x + (logoC.x - gridC.x) * easeInOutCubic(seg(tt, 0.58f, 0.68f)),
                    gridC.y + (logoC.y - gridC.y) * easeInOutCubic(seg(tt, 0.58f, 0.68f)),
                )
                val gScale = 1f - 0.88f * convP
                withTransform({
                    translate(center.x, center.y)
                    scale(gScale, gScale, Offset.Zero)
                    translate(-gridC.x, -gridC.y)
                }) {
                    val left = gridC.x - gridW / 2f
                    val top = gridC.y - gridH / 2f
                    val midX = gridC.x
                    val midY = gridC.y
                    val corner = CornerRadius(12.dp.toPx())
                    val borderA = seg(tt, 0.27f, 0.34f) * gAlpha
                    drawRoundRect(PaneBg.copy(alpha = 0.94f * borderA), Offset(left, top), Size(gridW, gridH), corner)
                    drawRoundRect(
                        brand.copy(alpha = 0.30f * borderA), Offset(left, top), Size(gridW, gridH), corner,
                        style = Stroke(1.dp.toPx()),
                    )

                    // Divider sweeps: a hairline "cut" growing across the pane, with a glowing head.
                    val vP = seg(tt, 0.32f, 0.40f)
                    if (vP > 0f) {
                        drawLine(
                            brand.copy(alpha = 0.35f * gAlpha),
                            Offset(midX, top), Offset(midX, top + gridH * vP), 1.dp.toPx(),
                        )
                        if (vP < 1f) {
                            val head = Offset(midX, top + gridH * vP)
                            drawCircle(brand.copy(alpha = 0.45f * (1f - vP) * gAlpha), 16.dp.toPx(), head)
                            drawCircle(MarkLight.copy(alpha = 0.9f * (1f - vP) * gAlpha), 2.5.dp.toPx(), head)
                        }
                    }
                    val hP = seg(tt, 0.37f, 0.45f)
                    if (hP > 0f) {
                        val halfW = gridW / 2f
                        for ((hx0, dir) in listOf(left to 1f, left + gridW to -1f)) {
                            val hx1 = hx0 + dir * halfW * hP
                            drawLine(
                                brand.copy(alpha = 0.35f * gAlpha),
                                Offset(hx0, midY), Offset(hx1, midY), 1.dp.toPx(),
                            )
                        }
                        if (hP < 1f) {
                            for (hx in listOf(left + halfW * hP, left + gridW - halfW * hP)) {
                                val head = Offset(hx, midY)
                                drawCircle(brand.copy(alpha = 0.45f * (1f - hP) * gAlpha), 16.dp.toPx(), head)
                                drawCircle(MarkLight.copy(alpha = 0.9f * (1f - hP) * gAlpha), 2.5.dp.toPx(), head)
                            }
                        }
                    }

                    // Panes: header (agent :: task + status dot) + scrolling activity rows.
                    val hA = seg(tt, 0.42f, 0.48f) * gAlpha
                    val rA = seg(tt, 0.44f, 0.50f) * gAlpha
                    val rowH = 15.dp.toPx()
                    val pad = 14.dp.toPx()
                    val paneW = gridW / 2f
                    val paneH = gridH / 2f
                    val speeds = listOf(0.030f, 0.044f, 0.036f, 0.052f) // px per ms — parallax
                    for (pane in 0..3) {
                        val pl = left + (pane % 2) * paneW
                        val pt = top + (pane / 2) * paneH
                        if (hA > 0f) {
                            val dotC = headerColors[pane]
                            drawCircle(dotC.copy(alpha = hA), 3.dp.toPx(), Offset(pl + pad, pt + 12.dp.toPx()))
                            drawText(
                                headerLayouts[pane], Ink.copy(alpha = 0.85f * hA),
                                topLeft = Offset(pl + pad + 9.dp.toPx(), pt + 6.dp.toPx()),
                            )
                        }
                        if (rA > 0f) {
                            val rowsTop = pt + 30.dp.toPx()
                            val scrollPx = tt * DURATION_MS * speeds[pane]
                            val base = floor(scrollPx / rowH).toInt()
                            val frac = scrollPx % rowH
                            clipRect(pl + 2f, rowsTop, pl + paneW - 2f, pt + paneH - 6.dp.toPx()) {
                                var k = -1
                                while (rowsTop + k * rowH - frac < pt + paneH) {
                                    val y = rowsTop + k * rowH - frac
                                    if (y + rowH > rowsTop) {
                                        val idx = ((base + k) % ROW_STRINGS.size + ROW_STRINGS.size) % ROW_STRINGS.size
                                        val isOk = ROW_STRINGS[idx].startsWith("ok")
                                        val c = if (isOk) success else Dim
                                        drawText(
                                            rowLayouts[idx], c.copy(alpha = (if (isOk) 0.75f else 0.55f) * rA),
                                            topLeft = Offset(pl + pad, y),
                                        )
                                    }
                                    k++
                                }
                            }
                        }
                    }
                }
            }

            // --- converge: particles stream from the grid area into the logo center -------------
            if (tt in 0.56f..0.80f) {
                for (p in PARTICLES) {
                    val pp = seg(tt, 0.58f + p.delay, 0.58f + p.delay + 0.15f)
                    if (pp <= 0f || pp >= 1f) continue
                    val start = Offset(gridC.x + p.sx * gridW, gridC.y + p.sy * gridH)
                    val dirX = logoC.x - start.x
                    val dirY = logoC.y - start.y
                    val len = hypot(dirX, dirY)
                    if (len == 0f) continue
                    val mid = Offset((start.x + logoC.x) / 2f, (start.y + logoC.y) / 2f)
                    val ctrl = Offset(mid.x + (-dirY / len) * p.arc * gridW * 0.25f, mid.y + (dirX / len) * p.arc * gridW * 0.25f)
                    val a = min(pp / 0.12f, 1f) * (1f - seg(pp, 0.75f, 1f)) * 0.95f
                    val color = particleColors[p.colorIdx]
                    val pos = quadBezier(start, ctrl, logoC, easeInCubic(pp))
                    drawCircle(color.copy(alpha = a), (p.sizeDp / 2f).dp.toPx(), pos)
                    // one short trail segment behind each particle
                    val pos2 = quadBezier(start, ctrl, logoC, easeInCubic((pp - 0.055f).coerceAtLeast(0f)))
                    drawCircle(color.copy(alpha = a * 0.35f), (p.sizeDp / 4f).dp.toPx(), pos2)
                }
            }

            // --- bloom: radial glow behind the mark ----------------------------------------------
            val bloomP = seg(tt, 0.68f, 0.86f)
            if (bloomP > 0f) {
                val gA = bloomP * (1f - 0.3f * seg(tt, 0.92f, 1f))
                val glowR = (340.dp.toPx() * easeOutCubic(bloomP)).coerceAtLeast(1f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(brand.copy(alpha = 0.50f * gA), brand.copy(alpha = 0.10f * gA), Color.Transparent),
                        logoC, glowR,
                    ),
                    radius = glowR, center = logoC,
                )
                drawCircle(
                    Brush.radialGradient(listOf(MarkLight.copy(alpha = 0.35f * gA), Color.Transparent), logoC, 130.dp.toPx()),
                    radius = 130.dp.toPx(), center = logoC,
                )
            }

            // --- the mark: stroke draw-on cascade, then the fill fades over it -------------------
            if (tt > 0.68f) {
                val markScale = 0.82f + 0.18f * EaseOutBack.transform(seg(tt, 0.70f, 0.88f))
                val s = logoSize * markScale
                val fillA = seg(tt, 0.82f, 0.90f)
                withTransform({
                    with(SupermuxMark) { applyMarkTransform(s, Offset(logoC.x - s / 2f, logoC.y - s / 2f)) }
                }) {
                    // Stroke width must be expressed in glyph units: the transform stack scales by
                    // (s/1015)*0.1, so 1 glyph unit renders that fraction of a pixel.
                    val strokeW = 5.dp.toPx() / ((s / SupermuxMark.VIEW_BOX) * 0.1f)
                    val grad = Brush.linearGradient(
                        listOf(MarkLight, brand),
                        start = SupermuxMark.GRADIENT_START, end = SupermuxMark.GRADIENT_END,
                    )
                    SupermuxMark.paths.forEachIndexed { i, path ->
                        val trim = easeInOutCubic(seg(tt, 0.70f + i * 0.03f, 0.80f + i * 0.03f))
                        if (trim > 0f && fillA < 1f) {
                            val segPath = Path()
                            val m = SupermuxMark.measures[i]
                            m.getSegment(0f, m.length * trim, segPath, true)
                            drawPath(
                                segPath, grad, alpha = 1f - fillA,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                    if (fillA > 0f) {
                        SupermuxMark.paths.forEach { drawPath(it, grad, alpha = fillA) }
                    }
                }
            }

            // --- one diagonal scanline sweep across the mark -------------------------------------
            val scanP = seg(tt, 0.74f, 0.86f)
            if (scanP > 0f && scanP < 1f) {
                val bandW = 260f
                val x = -bandW + (size.width + 2f * bandW) * scanP
                val sweepA = 0.06f * sin(scanP * PI.toFloat())
                withTransform({ rotate(-16f, pivot = logoC) }) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = sweepA), Color.Transparent),
                            startX = x, endX = x + bandW,
                        ),
                        topLeft = Offset(x, -size.height),
                        size = Size(bandW, size.height * 3f),
                    )
                }
            }
        }

        // --- boot text layer (Compose text for multi-color mono spans) ---------------------------
        if (t.value < 0.36f) {
            val bootA = 1f - seg(t.value, 0.27f, 0.34f)
            val cmd = "\$ supermux"
            val typed = (floor(seg(t.value, 0.03f, 0.11f) * cmd.length)).toInt().coerceIn(0, cmd.length)
            val showCursor = t.value in 0.03f..0.31f
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(480.dp)
                    .offset(y = (-24).dp)
                    .graphicsLayer { alpha = bootA },
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = brand)) { append("$ ") }
                        withStyle(SpanStyle(color = Ink)) { append("supermux".take((typed - 2).coerceAtLeast(0))) }
                        if (showCursor) withStyle(SpanStyle(color = Ink.copy(alpha = if (cursorOn) 1f else 0f))) { append("_") }
                    },
                    fontFamily = MonoFontFamily, fontSize = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                val logLines = listOf(
                    ":: secure channel .......... " to true,
                    ":: 3 agents online ......... " to true,
                    ":: mux ready" to false,
                )
                logLines.forEachIndexed { i, (line, hasOk) ->
                    val lp = seg(t.value, 0.12f + i * 0.05f, 0.17f + i * 0.05f)
                    if (lp > 0f) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Dim)) { append(line) }
                                if (hasOk && lp > 0.55f) withStyle(SpanStyle(color = success)) { append("ok") }
                            },
                            fontFamily = MonoFontFamily, fontSize = 13.sp,
                            modifier = Modifier.graphicsLayer { alpha = easeOutCubic(min(lp / 0.4f, 1f)) },
                        )
                    }
                }
            }
        }

        // --- wordmark + tagline ------------------------------------------------------------------
        if (t.value > 0.84f) {
            val wmA = seg(t.value, 0.86f, 0.93f)
            val tgA = seg(t.value, 0.89f, 0.95f)
            val rise = 10.dp * (1f - easeOutCubic(wmA))
            // The logo sits at 44% of window height; the wordmark column goes just under it.
            val yOff = maxHeight * (0.44f - 0.50f) + 105.dp + 34.dp
            Column(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = yOff + rise),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "supermux",
                    fontFamily = GeistFontFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp, letterSpacing = (-0.68).sp, color = WordmarkInk,
                    modifier = Modifier.graphicsLayer { alpha = wmA },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "AFK. Still shipping.",
                    fontFamily = MonoFontFamily, fontSize = 13.sp, color = TaglineDim,
                    modifier = Modifier.graphicsLayer { alpha = tgA },
                )
            }
        }

        // --- skip hint ---------------------------------------------------------------------------
        Text(
            "click anywhere to skip",
            fontFamily = MonoFontFamily, fontSize = 11.sp, color = Dim,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .graphicsLayer { alpha = seg(t.value, 0.10f, 0.18f) * 0.6f * (1f - seg(t.value, 0.92f, 1f)) },
        )
    }
}
