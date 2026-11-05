package dev.supermux.webview.sample.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.supermux.webview.ConsoleLevel
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebSurfaceHints
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.sample.Screen
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tags the chat's tests and UI flows find things by. */
object ChatTags {
    const val List = "chat-list"
    const val Menu = "chat-menu"
    const val Composer = "chat-composer"
    const val Send = "chat-send"
    const val Latest = "chat-latest"

    fun preview(slug: String) = "preview-$slug"

    fun menuItem(name: String) = "chat-menu-$name"
}

/** The widest the conversation gets: on a tablet, a desktop window or an unfolded Fold it sits in the middle. */
private val MAX_CHAT_WIDTH = 720.dp

/** The list's margins, left and right together. */
private val LIST_MARGINS = 24.dp

/** The agent's avatar, and with the gap after it, what it takes of an agent's row before its bubble and previews. */
private val AVATAR = 32.dp
private val AVATAR_SPACE = 40.dp

private val USER_SHAPE = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val AGENT_SHAPE = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
private val CARD_SHAPE = RoundedCornerShape(18.dp)
private val PILL = RoundedCornerShape(50)

/** The caption's lines in the measuring runs ([PreviewMarkers]): pure cyan and pure blue, which nothing else in the chat is. */
private val MARKER_CYAN = Color(0xFF00FFFF)
private val MARKER_BLUE = Color(0xFF0000FF)

/** An embed: a drag that starts on its page scrolls the chat (iOS), the wheel scrolls the chat (macOS). */
private val EMBED = WebSurfaceHints(inScrollingList = true)

/** A pane: its page takes every touch and the wheel. For drag-to-rotate, a game, a card that scrolls inside itself. */
private val PANE = WebSurfaceHints(inScrollingList = false)

/**
 * The chat demo: an agent answering with live previews, 100 messages and 49 previews, each a web view in the list
 * (`Activation.WhenVisible`, the default: live once on screen and still), parked when it scrolls away, and covered by
 * its last snapshot until it is live again. [onOpen] shows another of the sample's screens.
 */
@Composable
fun ChatScreen(chat: ChatState, onOpen: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val fling = ScrollableDefaults.flingBehavior()
    val request = chat.requested
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        try {
            chat.execute(request, density, fling)
        } finally {
            chat.requested = null
        }
    }
    LaunchedEffect(chat) { chat.notices.collectLatest { chat.snackbars.showSnackbar(it) } }
    // three.js and the kit, read from the resources before the first 3D preview asks for them.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { PreviewAssets.warm() } }
    // The probe looks at every frame, before it is laid out: what the last one showed.
    val probe = chat.probe
    if (probe != null) {
        LaunchedEffect(probe, density) {
            probe.density = density
            while (true) withFrameNanos { probe.frame(it, chat.list.layoutInfo) }
        }
    }
    val scope = rememberCoroutineScope()
    ChatTheme {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopBar(chat, onOpen)
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    MessageList(chat)
                    JumpToLatest(chat, onClick = { scope.launch { chat.list.animateScrollToItem(0) } }, Modifier.align(Alignment.BottomEnd))
                    val shape = RoundedCornerShape(12.dp)
                    SnackbarHost(chat.snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) { data ->
                        Snackbar(data, Modifier.webViewOverlay(shape), shape = shape)
                    }
                }
                Composer(chat, onSend = { text -> scope.launch { chat.send(text) } })
            }
        }
    }
}

/** Material 3 from an indigo seed, light or dark as the system is. */
@Composable
private fun ChatTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFC2C1FF), onPrimary = Color(0xFF2A2878),
            primaryContainer = Color(0xFF413F8F), onPrimaryContainer = Color(0xFFE2E1FF),
            secondaryContainer = Color(0xFF45455C), onSecondaryContainer = Color(0xFFE2E0F9),
            surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9),
            surfaceVariant = Color(0xFF47464F), onSurfaceVariant = Color(0xFFC8C5D0),
            surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1B1B21),
            surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF2A292F),
            surfaceContainerHighest = Color(0xFF35343A), outline = Color(0xFF918F9A), outlineVariant = Color(0xFF3A3940),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF4F46E5), onPrimary = Color.White,
            primaryContainer = Color(0xFFE2E1FF), onPrimaryContainer = Color(0xFF1C1A5E),
            secondaryContainer = Color(0xFFE0E1F9), onSecondaryContainer = Color(0xFF181A2C),
            surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
            surfaceVariant = Color(0xFFE4E1EC), onSurfaceVariant = Color(0xFF5A5964),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FA),
            surfaceContainer = Color(0xFFEFECF4), surfaceContainerHigh = Color(0xFFE9E7EF),
            surfaceContainerHighest = Color(0xFFE4E1E9), outline = Color(0xFF787680), outlineVariant = Color(0xFFD5D2DD),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun TopBar(chat: ChatState, onOpen: (Screen) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .height(64.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Preview agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Status(chat)
                }
                Menu(chat, onOpen)
            }
            HorizontalDivider(color = cs.outlineVariant)
        }
    }
}

/** "online · 49 live previews", or the run under way. */
@Composable
private fun Status(chat: ChatState) {
    val cs = MaterialTheme.colorScheme
    val running = chat.running
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (running != null) Color(0xFFF59E0B) else Color(0xFF22C55E), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            if (running != null) "measuring · ${running.label.lowercase()}" else "online · ${chat.previewCount} live previews",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Menu(chat: ChatState, onOpen: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(ChatTags.Menu)) { DotsIcon(cs.onSurface) }
        val shape = MenuDefaults.shape
        // Marked: on macOS a press goes to whatever is under the pointer natively, and a menu over a live page would
        // give the page its presses.
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.webViewOverlay(shape), shape = shape) {
            val idle = chat.running == null
            DropdownMenuItem(
                text = { Text("Measure: read the chat") },
                onClick = { open = false; chat.start(ChatRun.Read) },
                enabled = idle,
                modifier = Modifier.testTag(ChatTags.menuItem("read")),
            )
            DropdownMenuItem(
                text = { Text("Measure: fling the chat") },
                onClick = { open = false; chat.start(ChatRun.Fling) },
                enabled = idle,
                modifier = Modifier.testTag(ChatTags.menuItem("fling")),
            )
            DropdownMenuItem(
                text = { Text(if (chat.baseline) "Show live previews" else "Baseline: still pictures") },
                onClick = { open = false; chat.baseline = !chat.baseline },
                enabled = idle,
                modifier = Modifier.testTag(ChatTags.menuItem("baseline")),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Perf lab") },
                onClick = { open = false; onOpen(Screen.PerfLab) },
                modifier = Modifier.testTag(ChatTags.menuItem("perflab")),
            )
            DropdownMenuItem(
                text = { Text("Overlay demo") },
                onClick = { open = false; onOpen(Screen.Overlays) },
                modifier = Modifier.testTag(ChatTags.menuItem("overlays")),
            )
            DropdownMenuItem(
                text = { Text("Engine checks") },
                onClick = { open = false; onOpen(Screen.Checks) },
                modifier = Modifier.testTag(ChatTags.menuItem("checks")),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Simulate memory pressure") },
                onClick = {
                    open = false
                    chat.lab.memoryPressure()
                    chat.notify("Memory pressure: spares and parked pages let go, the budget halved for 30 s")
                },
                modifier = Modifier.testTag(ChatTags.menuItem("pressure")),
            )
        }
    }
}

@Composable
private fun MessageList(chat: ChatState) {
    val probe = chat.probe
    val probed = if (probe == null) {
        Modifier
    } else {
        Modifier.nestedScroll(probe.connection).onGloballyPositioned { c ->
            val bounds = c.boundsInWindow()
            probe.viewport(bounds.top, bounds.bottom)
        }
    }
    // The previews' card width: the list's, less its margins, at most the conversation's, less the agent's avatar. A
    // preview whose page follows its height is laid out at its page's height there from its first frame (initialFor).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = min(maxWidth - LIST_MARGINS, MAX_CHAT_WIDTH) - AVATAR_SPACE
        Messages(chat, probe, probed, cardWidth)
    }
}

@Composable
private fun Messages(chat: ChatState, probe: ChatProbe?, probed: Modifier, cardWidth: Dp) {
    LazyColumn(
        state = chat.list,
        reverseLayout = true,
        modifier = probed.fillMaxSize().testTag(ChatTags.List),
        contentPadding = PaddingValues(start = LIST_MARGINS / 2, end = LIST_MARGINS / 2, top = 12.dp, bottom = 16.dp),
    ) {
        items(
            count = chat.items.size,
            key = { chat.itemAt(it).id },
            contentType = { index ->
                when (chat.itemAt(index)) {
                    is UserMessage -> 0
                    is AgentMessage -> 1
                    is DayMarker -> 2
                    is TypingIndicator -> 3
                }
            },
        ) { index ->
            val item = chat.itemAt(index)
            // The row above this one, older: a new speaker gets more room than the same one going on.
            val above = chat.items.getOrNull(chat.items.size - 2 - index)
            val gap = when {
                item is DayMarker || above is DayMarker -> 14.dp
                above == null -> 0.dp
                (item is UserMessage) == (above is UserMessage) -> 6.dp
                else -> 16.dp
            }
            val placed = if (probe == null) {
                Modifier
            } else {
                val key = item.id
                DisposableEffect(probe, key) { onDispose { probe.gone(key) } }
                Modifier.onGloballyPositioned { c -> probe.placed(key, c.positionInWindow().y, c.size.height) }
            }
            Box(placed.fillMaxWidth().padding(top = gap), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MAX_CHAT_WIDTH).fillMaxWidth()) {
                    when (item) {
                        is UserMessage -> UserRow(item)
                        is AgentMessage -> AgentRow(item, chat, cardWidth)
                        is DayMarker -> DayRow(item)
                        is TypingIndicator -> TypingRow()
                    }
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: DayMarker) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            day.label,
            Modifier.background(cs.surfaceContainerHigh, PILL).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserRow(message: UserMessage) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalAlignment = Alignment.End) {
        Text(
            message.text,
            Modifier.background(cs.primaryContainer, USER_SHAPE).padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onPrimaryContainer,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "${message.time}  ✓✓",
            Modifier.padding(end = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentRow(message: AgentMessage, chat: ChatState, cardWidth: Dp) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        AgentAvatar(AVATAR)
        Spacer(Modifier.width(AVATAR_SPACE - AVATAR))
        Column(Modifier.weight(1f)) {
            // One text for the name and the time: a row that scrolls in lays out as few texts as it can.
            val small = MaterialTheme.typography.labelSmall
            val header = remember(message.time, cs.onSurfaceVariant, small) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Agent") }
                    withStyle(SpanStyle(color = cs.onSurfaceVariant, fontSize = small.fontSize)) { append("  ${message.time}") }
                }
            }
            Text(header, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                message.text,
                Modifier.padding(end = 28.dp).background(cs.surfaceContainerHigh, AGENT_SHAPE).padding(horizontal = 14.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
            )
            for (preview in message.previews) {
                Spacer(Modifier.height(8.dp))
                val initial = preview.initialFor(cardWidth)
                if (chat.baseline) StillCard(preview, initial) else PreviewCard(preview, initial, chat)
            }
        }
    }
}

/**
 * One live preview: its web view in a rounded card, with a caption under it. The state is the item's own, keyed by
 * the preview's content: an item that scrolls back is a new view of the same page, which shows its snapshot at its
 * height at once and adopts the page while it is still parked. A page that follows its height is laid out at
 * [initial] until it reports one: its own height on this card ([PreviewSpec.initialFor]).
 */
@Composable
private fun PreviewCard(spec: PreviewSpec, initial: Dp, chat: ChatState) {
    val cs = MaterialTheme.colorScheme
    val state = rememberWebViewState(spec.content)
    // On the state: handlers outlive engines, so one registration serves every engine that shows the page.
    DisposableEffect(state) {
        val undo = chat.bind(spec, state)
        onDispose { undo() }
    }
    // A page's errors, where whoever runs the app reads its output (logcat's System.out, the iOS console, stdout).
    LaunchedEffect(state) {
        state.console.collect { line ->
            if (line.level == ConsoleLevel.Error) println("chat-preview ${spec.slug}: ${line.message} (${line.source}:${line.line})")
        }
    }
    val probe = chat.probe
    val probed = if (probe == null) {
        Modifier
    } else {
        LaunchedEffect(state, probe) {
            snapshotFlow { state.phase.toString() to state.contentSize?.height?.value }
                .collect { (phase, reported) -> probe.cardState(spec.slug, phase, reported) }
        }
        DisposableEffect(state, probe) { onDispose { probe.cardGone(spec.slug) } }
        val guess = if (spec.height == null) initial.value else Float.NaN
        Modifier.onGloballyPositioned { c -> probe.card(spec.slug, guess, c.positionInWindow().y, c.size.height) }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(cs.surfaceContainerLow)
            .border(1.dp, cs.outlineVariant, CARD_SHAPE),
    ) {
        CompositionLocalProvider(LocalWebSurfaceHints provides if (spec.pane) PANE else EMBED) {
            val height = spec.height
            WebView(
                state,
                modifier = probed.fillMaxWidth()
                    .then(if (height != null) Modifier.height(height) else Modifier)
                    .testTag(ChatTags.preview(spec.slug)),
                sizing = if (height != null) WebSizing.Fill else WebSizing.WrapContentHeight(max = PreviewSpec.MAX_WRAP, initial = initial),
                placeholder = { PreviewPlaceholder(spec) },
            )
        }
        Caption(spec, state, chat)
    }
}

/** The baseline's card: the same card at the preview's height, with its placeholder where the web view would be. */
@Composable
private fun StillCard(spec: PreviewSpec, initial: Dp) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(cs.surfaceContainerLow)
            .border(1.dp, cs.outlineVariant, CARD_SHAPE),
    ) {
        Box(Modifier.fillMaxWidth().height(spec.height ?: initial)) { PreviewPlaceholder(spec) }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text("still", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Covers a preview until it has a snapshot: its own colours, so the page's first frame is no surprise. Opaque. */
@Composable
private fun PreviewPlaceholder(spec: PreviewSpec) {
    val cs = MaterialTheme.colorScheme
    val stage = spec.stage
    val base = cs.surfaceContainerHigh
    val brush = remember(stage, base, spec.accent) {
        if (stage != null) Brush.verticalGradient(stage) else Brush.verticalGradient(listOf(base, lerp(base, spec.accent, 0.22f)))
    }
    val ink = when {
        stage == null -> cs.onSurfaceVariant
        stage.first().luminance() > 0.5f -> Color.Black.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.72f)
    }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        Text("${spec.kind.label} · ${spec.title}", style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

@Composable
private fun Caption(spec: PreviewSpec, state: WebViewState, chat: ChatState) {
    val cs = MaterialTheme.colorScheme
    // The measuring runs' line along the page's bottom (PreviewMarkers): cyan while the page itself shows, blue while a
    // picture of it does, which Compose draws in its card.
    val marker = if (PreviewMarkers.enabled) {
        val ink = if (state.phase == WebViewPhase.Live) MARKER_CYAN else MARKER_BLUE
        Modifier.drawBehind { drawRect(ink, size = Size(size.width, PreviewMarkers.THICKNESS.dp.toPx())) }
    } else {
        Modifier
    }
    Row(
        marker.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.background(cs.surfaceContainerHighest, PILL).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(spec.accent, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(spec.kind.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        val title = remember(spec, cs.onSurfaceVariant) {
            buildAnnotatedString {
                append(spec.title)
                spec.hint?.let { hint -> withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(" · $hint") } }
            }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BridgeNote(spec, chat)
        Phase(state)
    }
}

/** What the page last told the app, where it says something worth showing. */
@Composable
private fun BridgeNote(spec: PreviewSpec, chat: ChatState) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall
    when (spec.bridge) {
        PreviewBridge.Colour -> {
            val hex = chat.colours[spec.slug] ?: return
            val colour = remember(hex) { parseHex(hex) } ?: return
            Box(Modifier.size(12.dp).background(colour, CircleShape).border(1.dp, cs.outlineVariant, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("app sees $hex", style = style, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        PreviewBridge.Counter -> {
            Text("app count ${chat.count}", style = style, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        PreviewBridge.Game -> if (chat.best > 0) {
            Text("best ${chat.best}", style = style, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        else -> Unit
    }
}

/** Whether the page is live, starting, or a snapshot until it is: the library at work. */
@Composable
private fun Phase(state: WebViewState) {
    val (label, colour) = when (state.phase) {
        WebViewPhase.Live -> "live" to Color(0xFF22C55E)
        WebViewPhase.Activating -> "starting" to Color(0xFFF59E0B)
        WebViewPhase.Cold -> "paused" to Color(0xFF9CA3AF)
        WebViewPhase.Crashed -> "crashed" to Color(0xFFEF4444)
        else -> "off" to Color(0xFF9CA3AF)
    }
    Box(Modifier.size(6.dp).background(colour, CircleShape))
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TypingRow() {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AgentAvatar(32.dp)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.background(cs.surfaceContainerHigh, AGENT_SHAPE).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                Box(
                    Modifier.size(7.dp)
                        .graphicsLayer {
                            val t = ((phase - i * 0.18f) % 1f + 1f) % 1f
                            val lift = if (t < 0.5f) t * 2f else (1f - t) * 2f
                            translationY = -lift * 4.dp.toPx()
                            alpha = 0.35f + lift * 0.65f
                        }
                        .background(cs.onSurfaceVariant, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun JumpToLatest(chat: ChatState, onClick: () -> Unit, modifier: Modifier) {
    val away by remember(chat) { derivedStateOf { chat.list.firstVisibleItemIndex > 2 } }
    AnimatedVisibility(away, modifier.padding(12.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.webViewOverlay(CircleShape).testTag(ChatTags.Latest),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            ArrowIcon(MaterialTheme.colorScheme.onSecondaryContainer, up = false)
        }
    }
}

@Composable
private fun Composer(chat: ChatState, onSend: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    fun send() {
        val text = chat.draft
        if (text.isBlank()) return
        chat.draft = ""
        onSend(text)
    }
    Surface(color = cs.surfaceContainer) {
        Column {
            HorizontalDivider(color = cs.outlineVariant)
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { chat.notify("Attachments aren't part of this demo") }) { PlusIcon(cs.onSurfaceVariant) }
                BasicTextField(
                    value = chat.draft,
                    onValueChange = { chat.draft = it },
                    modifier = Modifier.weight(1f)
                        .heightIn(min = 44.dp)
                        .background(cs.surfaceContainerHighest, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                        .testTag(ChatTags.Composer),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (chat.draft.isEmpty()) {
                                Text("Ask for a preview…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                            }
                            field()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { send() },
                    enabled = chat.draft.isNotBlank(),
                    modifier = Modifier.size(44.dp).testTag(ChatTags.Send),
                ) {
                    ArrowIcon(if (chat.draft.isNotBlank()) cs.onPrimary else cs.onSurfaceVariant, up = true)
                }
            }
        }
    }
}

/** The agent's avatar: a gradient disc with a four-pointed spark. Drawn from a cache: nothing is allocated per frame. */
@Composable
private fun AgentAvatar(size: Dp) {
    Box(
        Modifier.size(size).drawWithCache {
            val brush = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)), Offset.Zero, Offset(this.size.width, this.size.height))
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val r = this.size.minDimension * 0.27f
            val spark = Path().apply {
                moveTo(c.x, c.y - r)
                quadraticTo(c.x, c.y, c.x + r, c.y)
                quadraticTo(c.x, c.y, c.x, c.y + r)
                quadraticTo(c.x, c.y, c.x - r, c.y)
                quadraticTo(c.x, c.y, c.x, c.y - r)
                close()
            }
            onDrawBehind {
                drawCircle(brush)
                drawPath(spark, Color.White)
            }
        },
    )
}

@Composable
private fun DotsIcon(color: Color) {
    Box(
        Modifier.size(24.dp).drawBehind {
            val r = 2.dp.toPx()
            val step = 6.dp.toPx()
            for (i in -1..1) drawCircle(color, r, Offset(center.x, center.y + i * step))
        },
    )
}

@Composable
private fun PlusIcon(color: Color) {
    Box(
        Modifier.size(22.dp).drawBehind {
            val w = 2.dp.toPx()
            val inset = size.width * 0.18f
            drawLine(color, Offset(center.x, inset), Offset(center.x, size.height - inset), w, StrokeCap.Round)
            drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), w, StrokeCap.Round)
        },
    )
}

@Composable
private fun ArrowIcon(color: Color, up: Boolean) {
    Box(
        Modifier.size(20.dp).drawBehind {
            val w = 2.2.dp.toPx()
            val s = size.width
            val tip = if (up) s * 0.2f else s * 0.8f
            val tail = if (up) s * 0.82f else s * 0.18f
            val wing = if (up) s * 0.48f else s * 0.52f
            drawLine(color, Offset(s / 2, tail), Offset(s / 2, tip), w, StrokeCap.Round)
            drawLine(color, Offset(s / 2, tip), Offset(s * 0.22f, wing), w, StrokeCap.Round)
            drawLine(color, Offset(s / 2, tip), Offset(s * 0.78f, wing), w, StrokeCap.Round)
        },
    )
}

/** `#rrggbb` as a colour, or null. */
private fun parseHex(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    val value = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}
