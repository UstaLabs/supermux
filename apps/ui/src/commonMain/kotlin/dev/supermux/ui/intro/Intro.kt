// The ONE first-run intro (cluster G6).
//
// WHO WINS — a documented deviation from "desktop is the base": **Android's**
// `pairing/OnboardingIntro.kt` is the base here. It is the richer piece (an animated sky that
// advances a time of day per page, film grain, code stamps, staggered word reveals, agent marks
// and a live "meanwhile, on your computer" ticker) and it is compact-first, which is where a
// first run actually happens. Desktop's `intro/FirstRunIntro.kt` — the 4.8s "mux boot" cinematic
// and the real logo mark it draws — is folded in below as [FirstRunIntroOverlay] +
// [SupermuxMark], unchanged, and becomes the **Expanded** opening act of the same flow.
//
// BRANCH KEYS (two different questions, two different locals):
//   - **Width class** picks the LAYOUT variant. The cinematic's choreography is a 2×2 agent-pane
//     grid at 60% of the window width converging into a 210dp mark: on a phone that is a
//     letterboxed smudge, on a wide window it is the piece. So Expanded plays the cinematic
//     first and then constrains the pager's pages to a centred [IntroPageMaxWidth] column
//     (28dp gutters stretched across 1600px are not a layout). Compact goes straight to the
//     full-bleed pager, exactly as Android's flow always did.
//   - **Pointer** (`LocalPointerAvailable`) picks HIT TARGETS only — the next-page CTA, the scan
//     hero and the day-rail dots shrink for a mouse. That is a finger-vs-cursor question, not a
//     window-size one: an Android tablet with no mouse is Expanded and still needs 56dp.
//
// Everything platform-shaped left with the move: `R.drawable.agent_*` are `:ui`
// `composeResources` (`Res.drawable.agent_*`), the QR scan goes through `Platform.scanQr()`
// gated on `Caps.camera`, `PairingViewModel` is `:shared`'s `PairingState`, and the cinematic's
// `System.getenv("SM_INTRO_FREEZE")` hook is a [freezeAt] parameter the entry point fills in.
package dev.supermux.ui.intro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.PairUrl
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.PairingUiState
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.agent_claude
import dev.supermux.ui.resources.agent_codex
import dev.supermux.ui.resources.agent_cursor
import dev.supermux.ui.resources.agent_grok
import dev.supermux.ui.theme.GeistFontFamily
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The intro design's version. Bumping it re-shows a redesigned intro to existing users, because
 * `SettingsKeys.INTRO_SEEN` stores the version that was seen, not a boolean. Carried over from
 * desktop's `IntroStateStore.INTRO_VERSION`.
 */
const val INTRO_VERSION = 1

/** Expanded windows read the pages in a centred column instead of one 1600px-wide line. */
val IntroPageMaxWidth = 560.dp

/**
 * Should the first-run intro play? Pure, so both entry points share one policy (moved verbatim
 * from desktop's `IntroStateStore.shouldShow`):
 * - `SM_INTRO=1` → always show (screenshot/dev runs; the caller must NOT persist the seen flag,
 *   so a forced run never consumes a real user's one viewing).
 * - `SM_INTRO=0` → never show.
 * - `SM_PAIR_TOKEN` set → never show: that is a seeded dev/CI run (the pairing seed bypasses
 *   onboarding) and the overlay would hijack every headless verification screenshot.
 * - otherwise → show exactly once, until the seen flag is written.
 */
fun shouldShowIntro(envIntro: String?, envPairToken: String?, seen: Boolean): Boolean = when {
    envIntro == "1" -> true
    envIntro == "0" -> false
    !envPairToken.isNullOrBlank() -> false
    else -> !seen
}

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
 * Cinematic first-launch flow — the "A day, scrolled" language of supermux.dev: an animated sky
 * that advances a time of day per page, film grain, code stamps, staggered word reveals, agent
 * marks and a live "meanwhile, on your computer" terminal ticker. It ends on the connect page,
 * which drives the same [PairingState] scan / paste / manual path [OnboardingScreen] does.
 *
 * On an **Expanded** window the desktop cinematic ([FirstRunIntroOverlay]) plays over page 0
 * first and hands off to the pager when it finishes or is skipped; on **Compact** the pager
 * starts immediately (Android's behaviour, unchanged).
 *
 * @param showCinematic test seam / entry-point override for the width-class decision above.
 * @param freezeAt frozen cinematic timeline for deterministic screenshots (`SM_INTRO_FREEZE`).
 */
@Composable
fun OnboardingFlow(
    pairing: PairingState,
    onPaired: () -> Unit,
    initialDeepLink: PairUrl? = null,
    modifier: Modifier = Modifier,
    showCinematic: Boolean = LocalWindowWidthClass.current == WindowWidthClass.Expanded,
    freezeAt: Float? = null,
) {
    val dark = isDarkScheme()
    val ink = if (dark) NightInk else Ink
    val inkSoft = if (dark) NightSoft else InkSoft
    val expanded = LocalWindowWidthClass.current == WindowWidthClass.Expanded
    val pointer = LocalPointerAvailable.current
    val uiPrefs = LocalUiPrefs.current

    // A pair link opens straight on the connect page. The link is only read by
    // ConnectPage, so starting at page 0 meant a scanned QR code did nothing at
    // all on a fresh install — the intent was consumed and gone before anything
    // that cares about it composed. Someone arriving via a pair link has already
    // decided; the marketing carousel is not what they asked for.
    val connectPage = 3
    val pagerState = rememberPagerState(initialPage = if (initialDeepLink != null) connectPage else 0) { 4 }
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage

    // The seen flag is written ONCE, the first time the reader actually reaches the end of the
    // carousel (or arrives on it via a deep link). Desktop's marker file only ever recorded
    // "the intro played"; this is the same fact for a flow the user can page through.
    LaunchedEffect(page) {
        if (page >= connectPage) uiPrefs.putIntroSeen(INTRO_VERSION)
    }

    // Deep link arrival skips the cinematic too — the user has already decided.
    var cinematicDone by remember { mutableStateOf(!showCinematic || initialDeepLink != null) }

    val state by pairing.state.collectAsState()
    when (val s = state) {
        is PairingUiState.Confirm -> PairTofuDialog(
            pair = s.pair,
            deviceName = s.deviceName,
            onConfirm = { pairing.confirmPersist(s.pair) },
            onDismiss = { pairing.cancelConfirm() },
        )
        is PairingUiState.Paired -> LaunchedEffect(Unit) { onPaired() }
        else -> Unit
    }

    Box(modifier.fillMaxSize().testTag("intro_flow")) {
        SkyBackground(page = page, dark = dark)
        GrainOverlay()

        Column(Modifier.fillMaxSize().imePadding()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("intro_pager"),
            ) { p ->
                // Expanded reads in a centred column; Compact is full-bleed (Android's look).
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(if (expanded) Modifier.widthIn(max = IntroPageMaxWidth) else Modifier.fillMaxWidth()) {
                        when (p) {
                            0 -> HookPage(ink = ink, inkSoft = inkSoft)
                            1 -> AgentsPage(ink = ink, inkSoft = inkSoft)
                            2 -> AlwaysOnPage(ink = ink, inkSoft = inkSoft)
                            else -> ConnectPage(
                                ink = ink,
                                inkSoft = inkSoft,
                                pairing = pairing,
                                initialDeepLink = initialDeepLink,
                            )
                        }
                    }
                }
            }

            DayRail(
                page = page,
                pageCount = 4,
                ink = ink,
                pointer = pointer,
                onClick = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp),
            )

            if (page < 3) {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(if (pointer) 44.dp else 56.dp)
                        .testTag("intro_next"),
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

        // The Expanded opening act, above the already-composed pager so its exit fade is a real
        // reveal rather than a cut (exactly how desktop's Main.kt stacks it over the app).
        if (!cinematicDone) {
            FirstRunIntroOverlay(onFinished = { cinematicDone = true }, freezeAt = freezeAt)
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
    pointer: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.testTag("intro_day_rail"), verticalAlignment = Alignment.CenterVertically) {
        val dotH = if (pointer) 6.dp else 8.dp
        for (i in 0 until pageCount) {
            val width by animateFloatAsState(
                if (i == page) 26f else 8f,
                tween(350, easing = FastOutSlowInEasing),
                label = "dot$i",
            )
            Box(
                Modifier
                    .height(dotH)
                    .width(width.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i <= page) Teal else ink.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick(i) }
                    .testTag("intro_day_rail_$i"),
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

    PageColumn("intro_page_hook") {
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

private data class AgentMark(val res: DrawableResource, val tint: Color?)

@Composable
private fun AgentsPage(ink: Color, inkSoft: Color) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val agents = remember {
        listOf(
            AgentMark(Res.drawable.agent_claude, Coral),
            AgentMark(Res.drawable.agent_codex, null),
            AgentMark(Res.drawable.agent_cursor, SkyBlue),
            AgentMark(Res.drawable.agent_grok, Teal),
        )
    }

    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(550, delayMillis = 750), label = "agentsub")

    PageColumn("intro_page_agents") {
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

    PageColumn("intro_page_always_on") {
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
            .padding(18.dp)
            .testTag("intro_ticker"),
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
    pairing: PairingState,
    initialDeepLink: PairUrl?,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val pointer = LocalPointerAvailable.current
    val canScan = platform.caps.camera
    val state by pairing.state.collectAsState()
    val validating = state is PairingUiState.Validating

    var linkInput by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }
    // With no camera the paste field IS the primary path, so manual entry no longer hides
    // behind the QR hero — it is one tap away instead of two.
    var showManual by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null) pairing.validatePair(initialDeepLink)
    }

    val subA by animateFloatAsState(if (appeared) 1f else 0f, tween(400, delayMillis = 500), label = "connsub")
    val scanA by animateFloatAsState(if (appeared) 1f else 0f, tween(500, delayMillis = 600), label = "scan")
    val pasteA by animateFloatAsState(if (appeared) 1f else 0f, tween(400, delayMillis = 750), label = "paste")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 48.dp, bottom = 20.dp)
            .testTag("intro_page_connect"),
    ) {
        Stamp("pair your device", inkSoft, appeared)
        Spacer(Modifier.height(22.dp))
        WordReveal(listOf("Connect to", "your computer."), appeared, ink, "your computer.", size = 36)
        Spacer(Modifier.height(18.dp))
        Text(
            if (canScan) {
                "Open supermux on your computer and scan the pairing code — you'll be briefed in seconds."
            } else {
                "Run `bun run pair <device-name>` on your broker and paste the link — you'll be briefed in seconds."
            },
            fontFamily = GeistFontFamily,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = inkSoft,
            modifier = Modifier.graphicsLayer { alpha = subA },
        )
        Spacer(Modifier.height(26.dp))

        if (canScan) {
            Button(
                onClick = {
                    pairing.resetError()
                    scope.launch { platform.scanQr()?.let { pairing.validate(it, fallbackBase = null) } }
                },
                enabled = !validating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (pointer) 52.dp else 64.dp)
                    .graphicsLayer { alpha = scanA; translationY = (1f - scanA) * 18f }
                    .testTag("intro_scan"),
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
        }

        Column(Modifier.graphicsLayer { alpha = pasteA }) {
            if (canScan) {
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
            }
            val canSubmitPaste = !validating && linkInput.isNotBlank()
            val submitPaste = { pairing.validate(linkInput) }
            OutlinedTextField(
                value = linkInput,
                onValueChange = { linkInput = it; pairing.resetError() },
                placeholder = { Text("https://host/pair?t=…", fontFamily = MonoFontFamily, fontSize = 13.sp) },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                enabled = !validating,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("intro_paste_field"),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = submitPaste,
                enabled = canSubmitPaste,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("intro_paste_submit"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TealDeep, contentColor = Color.White),
            ) { Text("Pair", fontFamily = GeistFontFamily, fontWeight = FontWeight.SemiBold) }

            TextButton(
                onClick = { showManual = !showManual },
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("intro_manual_toggle"),
            ) {
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
                    onValueChange = { manualHost = it; pairing.resetError() },
                    label = { Text("Broker host") },
                    placeholder = {
                        Text(
                            if (pointer) "ws://127.0.0.1:9898" else "ws://10.0.2.2:9898",
                            fontFamily = MonoFontFamily,
                            fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    enabled = !validating,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("intro_manual_host"),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it; pairing.resetError() },
                    label = { Text("Device token") },
                    singleLine = true,
                    enabled = !validating,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("intro_manual_token"),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { pairing.validate(manualToken.trim(), fallbackBase = manualHost.trim()) },
                    enabled = !validating && manualHost.isNotBlank() && manualToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("intro_manual_submit"),
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
                modifier = Modifier.testTag("intro_error"),
            )
        }
    }
}

// ── shared page scaffold ────────────────────────────────────────────

@Composable
private fun PageColumn(tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp, bottom = 24.dp)
            .testTag(tag),
    ) {
        content()
    }
}

// ════════════════════════════════════════════════════════════════════════════════════════════
// The "mux boot" cinematic — desktop's `intro/FirstRunIntro.kt`, folded in verbatim.
// ════════════════════════════════════════════════════════════════════════════════════════════
//
// One linear timeline `t ∈ 0..1` over 4.8s drives every layer; each element maps its own
// sub-segment of t, so the whole piece is deterministic (and freezable for headless screenshots
// via [freezeAt] / `SM_INTRO_FREEZE=<t>`):
//
//   0.03–0.11  boot: "$ supermux" types itself, block cursor
//   0.12–0.26  boot log: ":: secure channel … ok" lines stream in
//   0.27–0.44  the terminal "splits": hairline sweeps cut a 2×2 tmux-style pane grid
//   0.42–0.62  four agent panes come alive (claude/codex/opencode/cursor), logs scrolling
//   0.58–0.76  converge: the grid collapses centerward into a 90-particle stream
//   0.68–0.90  bloom: radial teal glow + the REAL logo mark draws itself (stroke trim cascade),
//              fills, one diagonal scanline sweep passes over it
//   0.86–0.95  "supermux" wordmark + "AFK. Still shipping." tagline rise in
//   0.95–1.00  handoff: overlay fades, revealing whatever is already composed beneath
//
// Click / any key skips (quick fade). Everything renders in one Box: a full-screen Canvas for
// background/grid/particles/logo and two small Compose text layers (boot log, wordmark).

private const val DURATION_MS = 4_800

// Cinematic fixed palette (the intro is ALWAYS dark, independent of AppearanceMode); the brand
// teal + status colors come from LocalSemantics so the piece tracks the design-language palette.
private val CineBg = Color(0xFF060A0B)
private val PaneBg = Color(0xFF0A1112)
private val CineInk = Color(0xFFD3E2DD)
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
    val rnd = Random(7)
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

/**
 * The first-run cinematic. Shown once per install by the entry point's seen-flag gate
 * ([shouldShowIntro] + `SettingsKeys.INTRO_SEEN`), and by [OnboardingFlow] as the Expanded
 * opening act.
 *
 * @param freezeAt when non-null, pins the timeline at that `t` and never auto-advances or
 *   finishes — desktop's `SM_INTRO_FREEZE` phase-screenshot hook, now a parameter so no
 *   `System.getenv` reaches `:ui`.
 */
@Composable
fun FirstRunIntroOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    freezeAt: Float? = null,
) {
    val semantics = LocalSemantics.current
    val brand = semantics.brand
    val success = semantics.success
    val particleColors = listOf(brand, MarkLight, success, WordmarkInk)

    val scope = rememberCoroutineScope()
    val t = remember { Animatable(0f) }
    val skipAlpha = remember { Animatable(1f) }
    var finished by remember { mutableStateOf(false) }
    val freeze = remember(freezeAt) { freezeAt?.coerceIn(0f, 0.999f) }
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
        modifier
            .fillMaxSize()
            .testTag("first_run_intro")
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
            drawRect(CineBg)
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
                                headerLayouts[pane], CineInk.copy(alpha = 0.85f * hA),
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
                        withStyle(SpanStyle(color = CineInk)) { append("supermux".take((typed - 2).coerceAtLeast(0))) }
                        if (showCursor) withStyle(SpanStyle(color = CineInk.copy(alpha = if (cursorOn) 1f else 0f))) { append("_") }
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
                .testTag("first_run_intro_skip_hint")
                .graphicsLayer { alpha = seg(t.value, 0.10f, 0.18f) * 0.6f * (1f - seg(t.value, 0.92f, 1f)) },
        )
    }
}

/**
 * The supermux logo mark, ported from assets/logo/supermux.svg so the first-run intro (and any
 * future brand surface) can draw the REAL mark with Compose paths instead of shipping a raster.
 * The SVG is font-glyph output: three filled contours inside a 1015×1015 viewBox, y-flipped
 * through nested group transforms — [applyMarkTransform] reproduces that exact transform stack.
 */
object SupermuxMark {
    /** SVG viewBox edge length (the mark is square). */
    const val VIEW_BOX = 1015f

    // In viewBox space (after the group transforms below) the mark occupies x[165,865] y[70,945];
    // its visual center is ≈ (515, 507.5) — within 1% of the box center, so centering the box
    // centers the mark. Gradient endpoints should use these bounds, not the raw glyph coords.
    val GRADIENT_START = Offset(165f, 70f)
    val GRADIENT_END = Offset(865f, 945f)

    // The three contour `d` attributes, copied verbatim from assets/logo/supermux.svg (top bar,
    // middle bar, bottom bar incl. its inner arrow sub-contour).
    private val DATA = listOf(
        "M2880 8741 c-80 -16 -142 -67 -182 -149 l-33 -67 0 -384 0 -384 180 -84 c471 -221 866 -413 1772 -863 l982 -488 91 -89 c137 -131 239 -293 288 -458 13 -44 14 -266 13 -1730 -1 -924 -4 -1694 -7 -1710 l-5 -31 218 -104 c348 -165 369 -173 424 -167 84 10 149 59 192 146 21 45 22 58 27 416 3 204 5 1176 5 2160 l0 1790 -22 76 c-46 158 -130 303 -212 368 -22 17 -70 44 -108 60 -37 16 -225 106 -418 201 -192 95 -431 212 -530 260 -572 278 -858 416 -920 445 -66 30 -221 104 -735 350 -118 57 -359 174 -535 260 -280 137 -402 188 -440 184 -5 -1 -26 -4 -45 -8z",
        "M1702 7854 c-83 -41 -133 -114 -150 -220 -6 -32 -9 -242 -8 -468 l1 -409 370 -179 c780 -377 1362 -663 1965 -964 566 -282 631 -317 689 -370 137 -125 245 -295 293 -461 l23 -78 -4 -1720 -4 -1720 287 -138 c270 -129 290 -137 347 -137 73 0 123 23 170 79 75 86 72 59 79 806 4 369 9 1368 11 2220 l3 1550 -27 80 c-38 113 -112 239 -182 311 -56 57 -81 72 -410 236 -373 187 -1832 909 -2100 1038 -88 43 -317 155 -510 250 -472 232 -593 290 -639 306 -63 23 -144 18 -204 -12z",
        "M230 7051 c-113 -35 -195 -143 -220 -291 -8 -46 -10 -685 -8 -2190 l3 -2125 28 -78 c49 -141 92 -210 183 -299 33 -32 75 -68 94 -81 19 -13 224 -115 455 -227 231 -112 557 -270 725 -353 400 -196 1226 -601 1510 -739 124 -61 367 -180 540 -265 873 -431 845 -419 936 -394 76 21 162 110 183 191 6 20 12 953 16 2210 l7 2175 -27 90 c-36 123 -84 217 -153 297 -91 107 55 29 -1278 683 -451 222 -858 421 -905 443 -107 50 -843 410 -1454 711 -258 128 -488 236 -510 241 -47 11 -90 11 -125 1z m3217 -2765 c104 -49 137 -149 88 -266 -49 -117 -364 -817 -550 -1225 -101 -220 -229 -501 -284 -624 -135 -296 -144 -313 -179 -333 -17 -10 -50 -18 -76 -18 -38 0 -52 6 -82 33 l-36 32 -104 475 c-114 516 -116 520 -192 559 -20 10 -116 42 -212 71 -437 131 -690 210 -709 222 -25 15 -61 84 -61 116 0 30 18 74 42 99 12 13 80 46 152 74 72 28 203 80 291 114 172 68 1040 402 1345 517 102 39 248 95 325 124 77 29 151 54 165 54 14 0 48 -11 77 -24z",
    )

    /** Parsed once; raw SVG glyph coordinates — only meaningful inside [applyMarkTransform]. */
    val paths: List<Path> by lazy { DATA.map { PathParser().parsePathString(it).toPath() } }

    /**
     * One [PathMeasure] per path for stroke "draw-on" trims. Compose's PathMeasure only walks the
     * FIRST contour of a path — for the bottom bar (which carries the inner arrow as a second
     * contour) that means the trim sweeps the outer bar and the arrow pops in with the fill.
     */
    val measures: List<PathMeasure> by lazy {
        paths.map { PathMeasure().apply { setPath(it, false) } }
    }

    /**
     * Reproduces the SVG group stack — translate(165 70) → translate(0 875) → scale(0.1 -0.1) —
     * preceded by fitting the 1015-unit viewBox into a [sizePx] square at [topLeft]. After this,
     * drawing coordinates are viewBox units (e.g. [GRADIENT_START]/[GRADIENT_END]) and one pixel
     * of screen width equals 1f / totalScale path units, where totalScale = (sizePx/VIEW_BOX)*0.1f.
     */
    fun DrawTransform.applyMarkTransform(sizePx: Float, topLeft: Offset) {
        translate(topLeft.x, topLeft.y)
        scale(sizePx / VIEW_BOX, sizePx / VIEW_BOX, Offset.Zero)
        translate(165f, 70f)
        translate(0f, 875f)
        scale(0.1f, -0.1f, Offset.Zero)
    }
}
