package dev.supermux.android.pairing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermux.android.R
import dev.supermux.android.theme.GeistFontFamily
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.net.PairUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

// ── website palette ("A day, scrolled") ─────────────────────────────
private val Paper = Color(0xFFFAF7F2)
private val Ink = Color(0xFF101828)
private val InkSoft = Color(0xFF3D4A5E)
private val Teal = Color(0xFF0FB5A3)
private val TealDeep = Color(0xFF0A8D80)
private val Amber = Color(0xFFF5A524)
private val Coral = Color(0xFFFF6B57)
private val SkyBlue = Color(0xFF4AA8E8)
private val LeafGreen = Color(0xFF5FC26D)

private val NightInk = Color(0xFFE8EDF6)
private val NightSoft = Color(0xFFA7B4C8)

private data class SkyPhase(
    val top: Color,
    val mid: Color,
    val low: Color,
    val orb: Color,
    val orbX: Float,
    val orbY: Float,
    val orbScale: Float,
)

private fun lightPhases() = listOf(
    SkyPhase(Color(0xFFFFE3B8), Color(0xFFCFE9F4), Paper, Color(0xFFFFD469), 0.16f, 0.20f, 1.0f),
    SkyPhase(Color(0xFFCFE9F4), Color(0xFFE0F2F1), Paper, Color(0xFFFFDE85), 0.50f, 0.10f, 0.85f),
    SkyPhase(Color(0xFFFFD4B5), Color(0xFFF5DED1), Paper, Color(0xFFFFB36B), 0.82f, 0.24f, 0.9f),
    SkyPhase(Color(0xFFD9DCEE), Color(0xFFE5E7F2), Paper, Color(0xFFD9E2F5), 0.82f, 0.14f, 0.55f),
)

private fun darkPhases() = listOf(
    SkyPhase(Color(0xFF171C2B), Color(0xFF1A2130), Color(0xFF12161F), Color(0xFFF2F2E0), 0.78f, 0.16f, 0.6f),
    SkyPhase(Color(0xFF141A28), Color(0xFF171E2C), Color(0xFF10141C), Color(0xFFF2F2E0), 0.60f, 0.12f, 0.55f),
    SkyPhase(Color(0xFF10141F), Color(0xFF141A26), Color(0xFF0D1017), Color(0xFFE6EAF7), 0.85f, 0.10f, 0.45f),
    SkyPhase(Color(0xFF12161F), Color(0xFF151B28), Color(0xFF0F1219), Color(0xFFE6EAF7), 0.85f, 0.10f, 0.45f),
)

/**
 * Cinematic first-launch flow — the Android sibling of the iOS onboarding and the
 * supermux.dev "A day, scrolled" language: an animated sky that advances a time of
 * day per page, film grain, code stamps, staggered word reveals, agent marks and a
 * live "meanwhile, on your computer" terminal ticker. It ends on the connect page,
 * which reuses the proven [PairingViewModel] scan / paste / manual pairing path.
 */
@Composable
fun OnboardingFlow(
    onPaired: () -> Unit,
    initialDeepLink: PairUrl? = null,
    vm: PairingViewModel = viewModel(),
) {
    val dark = isDarkScheme()
    val ink = if (dark) NightInk else Ink
    val inkSoft = if (dark) NightSoft else InkSoft

    val pagerState = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage

    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is PairingUiState.Confirm -> PairTofuDialog(
            pair = s.pair,
            deviceName = s.deviceName,
            onConfirm = { vm.confirmPersist(s.pair) },
            onDismiss = { vm.cancelConfirm() },
        )
        is PairingUiState.Paired -> LaunchedEffect(Unit) { onPaired() }
        else -> Unit
    }

    Box(Modifier.fillMaxSize()) {
        SkyBackground(page = page, dark = dark)
        GrainOverlay()

        Column(Modifier.fillMaxSize().imePadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { p ->
                when (p) {
                    0 -> HookPage(ink = ink, inkSoft = inkSoft)
                    1 -> AgentsPage(ink = ink, inkSoft = inkSoft)
                    2 -> AlwaysOnPage(ink = ink, inkSoft = inkSoft)
                    else -> ConnectPage(
                        ink = ink,
                        inkSoft = inkSoft,
                        vm = vm,
                        initialDeepLink = initialDeepLink,
                    )
                }
            }

            DayRail(
                page = page,
                pageCount = 4,
                ink = ink,
                onClick = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp),
            )

            if (page < 3) {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dark) Color(0xFFE8EDF6) else Ink,
                        contentColor = if (dark) Ink else Color.White,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                ) {
                    Text(
                        when (page) {
                            0 -> "See what it does"
                            1 -> "It never sleeps"
                            else -> "Connect your computer"
                        },
                        fontFamily = GeistFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
                Spacer(Modifier.height(32.dp))
            } else {
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun isDarkScheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    val luma = 0.2126 * bg.red.toDouble().pow(2.2) +
        0.7152 * bg.green.toDouble().pow(2.2) +
        0.0722 * bg.blue.toDouble().pow(2.2)
    return luma < 0.2
}

// ── sky + atmosphere ────────────────────────────────────────────────

@Composable
private fun SkyBackground(page: Int, dark: Boolean) {
    val phases = if (dark) darkPhases() else lightPhases()
    val p = phases[page.coerceIn(0, phases.lastIndex)]

    val top by animateColorAsState(p.top, tween(900, easing = FastOutSlowInEasing), label = "top")
    val mid by animateColorAsState(p.mid, tween(900, easing = FastOutSlowInEasing), label = "mid")
    val low by animateColorAsState(p.low, tween(900, easing = FastOutSlowInEasing), label = "low")
    val orb by animateColorAsState(p.orb, tween(900, easing = FastOutSlowInEasing), label = "orb")
    val orbX by animateFloatAsState(p.orbX, tween(900, easing = FastOutSlowInEasing), label = "orbX")
    val orbY by animateFloatAsState(p.orbY, tween(900, easing = FastOutSlowInEasing), label = "orbY")
    val orbScale by animateFloatAsState(p.orbScale, tween(900, easing = FastOutSlowInEasing), label = "orbS")

    val cloudShift by rememberInfiniteTransition(label = "clouds").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(70_000, easing = LinearEasing), RepeatMode.Restart),
        label = "cloudShift",
    )

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, mid, low)))) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * orbX
            val cy = size.height * orbY
            val glow = 220f * orbScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orb, orb.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = glow,
                ),
                radius = glow,
                center = Offset(cx, cy),
            )
            drawCircle(color = orb, radius = 70f * orbScale, center = Offset(cx, cy))
        }

        Canvas(Modifier.fillMaxSize()) {
            val base = if (dark) 0.05f else 0.5f
            val w = size.width
            val x1 = -0.3f * w + cloudShift * 1.3f * w
            val x2 = 1.1f * w - cloudShift * 1.4f * w
            drawCloud(Color.White.copy(alpha = base), Offset(x1, size.height * 0.12f), 260f)
            drawCloud(Color.White.copy(alpha = base * 0.7f), Offset(x2, size.height * 0.30f), 190f)
        }
    }
}

private fun DrawScope.drawCloud(color: Color, origin: Offset, width: Float) {
    val h = width * 0.24f
    drawOval(color, topLeft = origin, size = Size(width * 0.5f, h))
    drawOval(color, topLeft = Offset(origin.x + width * 0.22f, origin.y - h * 0.5f), size = Size(width * 0.55f, h * 1.3f))
    drawOval(color, topLeft = Offset(origin.x + width * 0.5f, origin.y + h * 0.1f), size = Size(width * 0.5f, h))
}

@Composable
private fun GrainOverlay() {
    val dots = remember {
        val rng = Random(42)
        List(900) { Offset(rng.nextFloat(), rng.nextFloat()) to rng.nextFloat() }
    }
    Canvas(Modifier.fillMaxSize()) {
        dots.forEach { (o, g) ->
            drawRect(
                color = Color.White.copy(alpha = 0.03f + g * 0.03f),
                topLeft = Offset(o.x * size.width, o.y * size.height),
                size = Size(2f, 2f),
            )
        }
    }
}

// ── shared atoms ────────────────────────────────────────────────────

@Composable
private fun Stamp(text: String, inkSoft: Color, appeared: Boolean) {
    val a by animateFloatAsState(
        if (appeared) 1f else 0f,
        tween(500, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "stamp",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 12f },
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            color = inkSoft,
        )
    }
}

@Composable
private fun WordReveal(
    words: List<String>,
    appeared: Boolean,
    ink: Color,
    accentWord: String,
    size: Int = 44,
) {
    Column {
        words.forEachIndexed { i, word ->
            val a by animateFloatAsState(
                if (appeared) 1f else 0f,
                tween(550, delayMillis = 250 + i * 130, easing = FastOutSlowInEasing),
                label = "word$i",
            )
            Text(
                word,
                fontFamily = GeistFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = size.sp,
                lineHeight = (size + 4).sp,
                letterSpacing = (-1).sp,
                color = if (word == accentWord) Teal else ink,
                modifier = Modifier
                    .graphicsLayer { alpha = a; translationY = (1f - a) * 26f }
                    .blur(6.dp * (1f - a)),
            )
        }
    }
}

@Composable
private fun DayRail(
    page: Int,
    pageCount: Int,
    ink: Color,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until pageCount) {
            val width by animateFloatAsState(
                if (i == page) 26f else 8f,
                tween(350, easing = FastOutSlowInEasing),
                label = "dot$i",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i <= page) Teal else ink.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick(i) },
            )
            if (i < pageCount - 1) {
                Box(Modifier.width(10.dp).height(1.5.dp).background(ink.copy(alpha = 0.1f)))
            }
        }
    }
}

// ── page 1 · the hook ───────────────────────────────────────────────

@Composable
private fun HookPage(ink: Color, inkSoft: Color) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(550, delayMillis = 620), label = "sub")
    val trustA by animateFloatAsState(if (appeared) 1f else 0f, tween(500, delayMillis = 850), label = "trust")

    PageColumn {
        Stamp("open-source · mobile-first · ADE", inkSoft, appeared)
        Spacer(Modifier.height(22.dp))
        WordReveal(listOf("AFK.", "Still shipping."), appeared, ink, "Still shipping.")
        Spacer(Modifier.height(22.dp))
        Text(
            "supermux runs your coding agents on a computer you own — and hands you every session on every screen you carry.",
            fontFamily = GeistFontFamily,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            color = inkSoft,
            modifier = Modifier.graphicsLayer { alpha = subA; translationY = (1f - subA) * 16f },
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.graphicsLayer { alpha = trustA },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("MIT", "self-hosted", "no vendor cloud", "no account").forEachIndexed { i, item ->
                if (i > 0) {
                    Box(Modifier.size(2.5.dp).clip(CircleShape).background(inkSoft.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(item, fontFamily = MonoFontFamily, fontSize = 12.sp, color = inkSoft.copy(alpha = 0.85f))
                if (i < 3) Spacer(Modifier.width(6.dp))
            }
        }
    }
}

// ── page 2 · bring your own subscription ────────────────────────────

private data class AgentMark(val res: Int, val tint: Color?)

@Composable
private fun AgentsPage(ink: Color, inkSoft: Color) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val agents = listOf(
        AgentMark(R.drawable.agent_claude, Coral),
        AgentMark(R.drawable.agent_codex, null),
        AgentMark(R.drawable.agent_cursor, SkyBlue),
        AgentMark(R.drawable.agent_grok, Teal),
    )

    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(550, delayMillis = 750), label = "agentsub")

    PageColumn {
        Stamp("bring your own subscription", inkSoft, appeared)
        Spacer(Modifier.height(22.dp))
        WordReveal(listOf("Your agents.", "Your keys."), appeared, ink, "Your keys.")
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            agents.forEachIndexed { i, a ->
                val aAnim by animateFloatAsState(
                    if (appeared) 1f else 0f,
                    tween(500, delayMillis = 350 + i * 90, easing = FastOutSlowInEasing),
                    label = "agent$i",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            alpha = aAnim
                            scaleX = 0.4f + aAnim * 0.6f
                            scaleY = 0.4f + aAnim * 0.6f
                            translationY = (1f - aAnim) * 30f
                        }
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(12.dp),
                ) {
                    Image(
                        painter = painterResource(a.res),
                        contentDescription = null,
                        colorFilter = a.tint?.let { ColorFilter.tint(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Claude Code, Codex, Cursor, OpenCode & Grok — running on your machine, with your plan. No middleman, no markup.",
            fontFamily = GeistFontFamily,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            color = inkSoft,
            modifier = Modifier.graphicsLayer { alpha = subA; translationY = (1f - subA) * 16f },
        )
    }
}

// ── page 3 · always-on sessions ─────────────────────────────────────

@Composable
private fun AlwaysOnPage(ink: Color, inkSoft: Color) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(550, delayMillis = 700), label = "alwayssub")

    PageColumn {
        Stamp("always-on sessions", inkSoft, appeared)
        Spacer(Modifier.height(22.dp))
        WordReveal(listOf("You leave.", "It doesn't."), appeared, ink, "It doesn't.")
        Spacer(Modifier.height(24.dp))
        MeanwhileTicker(appeared)
        Spacer(Modifier.height(24.dp))
        Text(
            "Sessions live on your box, not a browser tab. Walk out the door and the work keeps moving — a push lands the moment you're needed.",
            fontFamily = GeistFontFamily,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            color = inkSoft,
            modifier = Modifier.graphicsLayer { alpha = subA; translationY = (1f - subA) * 16f },
        )
    }
}

@Composable
private fun MeanwhileTicker(appeared: Boolean) {
    var typedLines by remember { mutableStateOf(0) }
    var cursorOn by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(700)
        for (i in 0..4) {
            delay(480)
            typedLines = i
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(550)
            cursorOn = !cursorOn
        }
    }

    val lines = listOf(
        "▸ bun test — 212 passed" to LeafGreen,
        "▸ edit src/channels/web/session.ts" to Color(0xFFD1DBE8),
        "▸ commit \"wire session resume\"" to Color(0xFFD1DBE8),
        "▸ opening review — waiting on you" to Amber,
    )

    val cardA by animateFloatAsState(if (appeared) 1f else 0f, tween(550, delayMillis = 400), label = "ticker")

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = cardA; translationY = (1f - cardA) * 24f }
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121214).copy(alpha = 0.96f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Teal.copy(alpha = if (cursorOn) 1f else 0.3f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "meanwhile, on your computer",
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                color = Color(0xFFA6B5CC),
            )
        }
        Spacer(Modifier.height(12.dp))
        lines.forEachIndexed { i, (text, tint) ->
            val lineA by animateFloatAsState(
                if (i < typedLines) 1f else 0f,
                tween(400, easing = FastOutSlowInEasing),
                label = "line$i",
            )
            Text(
                text,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = tint,
                modifier = Modifier
                    .graphicsLayer { alpha = lineA; translationX = (1f - lineA) * -14f }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

// ── page 4 · connect ────────────────────────────────────────────────

@Composable
private fun ConnectPage(
    ink: Color,
    inkSoft: Color,
    vm: PairingViewModel,
    initialDeepLink: PairUrl?,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val state by vm.state.collectAsStateWithLifecycle()
    val validating = state is PairingUiState.Validating

    var linkInput by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }

    val qrLaunch = rememberQrScanLauncher { decoded ->
        if (decoded != null) vm.validate(decoded, fallbackBase = null)
    }
    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null) vm.validatePair(initialDeepLink)
    }

    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(400, delayMillis = 500), label = "connsub")
    val scanA by animateFloatAsState(if (appeared) 1f else 0f, tween(500, delayMillis = 600), label = "scan")
    val pasteA by animateFloatAsState(if (appeared) 1f else 0f, tween(400, delayMillis = 750), label = "paste")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 48.dp, bottom = 20.dp),
    ) {
        Stamp("pair your device", inkSoft, appeared)
        Spacer(Modifier.height(22.dp))
        WordReveal(listOf("Connect to", "your computer."), appeared, ink, "your computer.", size = 36)
        Spacer(Modifier.height(18.dp))
        Text(
            "Open supermux on your computer and scan the pairing code — you'll be briefed in seconds.",
            fontFamily = GeistFontFamily,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = inkSoft,
            modifier = Modifier.graphicsLayer { alpha = subA },
        )
        Spacer(Modifier.height(26.dp))

        Button(
            onClick = qrLaunch,
            enabled = !validating,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer { alpha = scanA; translationY = (1f - scanA) * 18f },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TealDeep, contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        "Scan pairing code",
                        fontFamily = GeistFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                    Text(
                        "shown by the supermux desktop app",
                        fontFamily = GeistFontFamily,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Column(Modifier.graphicsLayer { alpha = pasteA }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(ink.copy(alpha = 0.12f)))
                Text(
                    "  or paste a pairing link  ",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    color = inkSoft.copy(alpha = 0.7f),
                )
                Box(Modifier.weight(1f).height(1.dp).background(ink.copy(alpha = 0.12f)))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = linkInput,
                onValueChange = { linkInput = it; vm.resetError() },
                placeholder = { Text("https://host/pair?t=…", fontFamily = MonoFontFamily, fontSize = 13.sp) },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                enabled = !validating,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.validate(linkInput) },
                enabled = !validating && linkInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TealDeep, contentColor = Color.White),
            ) { Text("Pair", fontFamily = GeistFontFamily, fontWeight = FontWeight.SemiBold) }

            TextButton(onClick = { showManual = !showManual }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    if (showManual) "Hide manual entry" else "Manual entry",
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    color = inkSoft,
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            if (showManual) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it; vm.resetError() },
                    label = { Text("Broker host") },
                    placeholder = { Text("ws://10.0.2.2:9898", fontFamily = MonoFontFamily, fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !validating,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it; vm.resetError() },
                    label = { Text("Device token") },
                    singleLine = true,
                    enabled = !validating,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.validate(manualToken.trim(), fallbackBase = manualHost.trim()) },
                    enabled = !validating && manualHost.isNotBlank() && manualToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TealDeep, contentColor = Color.White),
                ) { Text("Pair", fontFamily = GeistFontFamily, fontWeight = FontWeight.SemiBold) }
            }
        }

        if (validating) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Teal, strokeWidth = 2.5.dp)
                Text("Pairing…", fontFamily = GeistFontFamily, fontSize = 14.sp, color = inkSoft)
            }
        }
        (state as? PairingUiState.Error)?.let { err ->
            Spacer(Modifier.height(14.dp))
            Text(
                err.message,
                fontFamily = GeistFontFamily,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Coral,
            )
        }
    }
}

// ── shared page scaffold ────────────────────────────────────────────

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp, bottom = 24.dp),
    ) {
        content()
    }
}
