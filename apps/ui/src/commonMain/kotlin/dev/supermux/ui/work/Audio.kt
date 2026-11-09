// The app's audio player on screen (#118, design A): the bottom bar's docked now-playing part and the rail's ring.
package dev.supermux.ui.work

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.chat.LocalChatSearch
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.shell.RailTile
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.widgets.ModalHost
import dev.supermux.work.AudioPlayer
import dev.supermux.work.NowPlaying

/** The fleet's [AudioPlayer] (#118), provided next to [LocalDownloads]. Null outside a fleet (a test, a preview). */
val LocalAudioPlayer = staticCompositionLocalOf<AudioPlayer?> { null }

/**
 * [LocalAudioPlayer], or — outside a fleet — a player on the library that lives as long as this composition: a
 * preview's voice note still plays, and stops with the screen that holds it.
 */
@Composable
fun rememberAudioPlayer(): AudioPlayer {
    LocalAudioPlayer.current?.let { return it }
    val scope = rememberCoroutineScope()
    val player = remember(scope) { AudioPlayer(LibraryAudioOutputs(scope)) }
    DisposableEffect(player) { onDispose { player.close() } }
    return player
}

/**
 * The bottom bar's now-playing part (#118, design A), docked above the host row while something plays — the same
 * bar on a phone's Home and on a desktop: ❚❚/▶, the title and the chat it came from (a tap opens that chat at the
 * message), the speed (1× → 1.5× → 2×) and ✕; under them a thin scrubber you drag, between the time played and the
 * length. Nothing while idle or outside a fleet. A sound the player cannot drive (#118 car 2: the OS voice reading a
 * message) shows its title, its chat and ✕ only.
 */
@Composable
fun NowPlayingDock(modifier: Modifier = Modifier) {
    val player = LocalAudioPlayer.current ?: return
    val now by player.state.collectAsState()
    val playing = now ?: return
    NowPlayingPart(player, playing, modifier)
}

/** The player's controls for [now]: the dock's, and the rail's popover's. */
@Composable
private fun NowPlayingPart(player: AudioPlayer, now: NowPlaying, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val touch = !LocalPointerAvailable.current
    val m = if (touch) DockTokens.Touch else DockTokens.Pointer
    val platform = LocalPlatform.current
    val search = LocalChatSearch.current
    Column(modifier.fillMaxWidth().padding(m.padding).testTag("now_playing")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = m.button),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.gap),
        ) {
            if (now.stopOnly) {
                // Nothing to pause: a quiet mark of the voice in the button's place, so the title lines up.
                Box(Modifier.size(m.button), contentAlignment = Alignment.Center) {
                    Icon(AudioGlyphs.Voice, contentDescription = null, tint = cs.primary, modifier = Modifier.size(m.glyph))
                }
            } else {
                QuietButton(
                    size = m.button,
                    corner = m.corner,
                    label = if (now.playing) "Pause" else "Play",
                    tag = "now_playing_toggle",
                    onClick = { player.toggle() },
                ) {
                    Icon(
                        if (now.playing) AudioGlyphs.Pause else AudioGlyphs.Play,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(m.glyph),
                    )
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    now.item.title,
                    color = cs.onSurface,
                    fontSize = m.title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    // The extension stays in view, as the downloads' names.
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("now_playing_title"),
                )
                val origin = now.item.origin
                val chat = now.item.chat
                if (origin != null && chat != null) {
                    ChatLink(chat, m.chat, Modifier.weight(1f, fill = false)) {
                        if (search?.reveal(origin.sessionId, origin.messageId) != true) {
                            platform.notices.show("That chat is no longer available")
                        }
                    }
                }
            }
            if (!now.stopOnly) QuietButton(
                size = m.speedHeight,
                width = null,
                corner = m.corner,
                label = "Speed",
                tag = "now_playing_speed",
                onClick = { player.nextSpeed() },
            ) {
                Text(
                    speedText(now.speed),
                    color = cs.onSurfaceVariant,
                    fontSize = m.speed,
                    modifier = Modifier.padding(horizontal = m.speedPadding),
                )
            }
            QuietButton(size = m.stop, corner = m.corner, label = "Stop", tag = "now_playing_stop", onClick = { player.stop() }) {
                Icon(AudioGlyphs.Stop, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(m.stopGlyph))
            }
        }
        if (!now.stopOnly) Scrubber(player, now, m)
    }
}

/** The chat it came from: quiet text that a pointer underlines; a tap opens it at the message. */
@Composable
private fun ChatLink(chat: String, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        chat,
        color = if (hovered) cs.onSurface else cs.onSurfaceVariant,
        fontSize = size,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        modifier = modifier
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Open the chat it came from", onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("now_playing_chat"),
    )
}

/**
 * The time played, a thin track filling in teal that you tap or drag (it moves there once let go), and the length.
 * Not Material's slider: a 2 dp hairline, and a small teal knob — always on a phone, under a pointer while hovered.
 */
@Composable
private fun Scrubber(player: AudioPlayer, now: NowPlaying, m: DockMeasures) {
    val cs = MaterialTheme.colorScheme
    val length = now.durationMs
    var dragged by remember { mutableStateOf<Float?>(null) }
    val fraction = dragged ?: if (length > 0) (now.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val knob = m.knobAlways || hovered || dragged != null
    val shownAt = if (dragged != null) (dragged!! * length).toLong() else now.positionMs
    Row(
        Modifier.fillMaxWidth().height(m.seekRow).padding(start = m.seekStart, end = m.seekEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeText(clock(shownAt), m, "now_playing_elapsed")
        Box(
            Modifier
                .weight(1f)
                .height(m.seekRow)
                .pointerHoverIcon(PointerIcon.Hand)
                .hoverable(source)
                .pointerInput(length) {
                    detectTapGestures { at ->
                        if (length > 0 && size.width > 0) player.seek((at.x / size.width * length).toLong())
                    }
                }
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragged?.let { if (length > 0) player.seek((it * length).toLong()) }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, _ ->
                        if (size.width > 0) dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .semantics {
                    contentDescription = "Position"
                    stateDescription = "${clock(now.positionMs)} of ${clock(length)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { to ->
                        if (length > 0) player.seek((to.coerceIn(0f, 1f) * length).toLong())
                        length > 0
                    }
                }
                .testTag("now_playing_seek"),
        ) {
            val track = cs.onSurface.copy(alpha = 0.10f)
            val fill = cs.primary
            Canvas(Modifier.matchParentSize()) {
                val line = DockTokens.Track.toPx()
                val y = size.height / 2f
                drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = line, cap = StrokeCap.Round)
                val x = size.width * fraction
                if (x > 0f) drawLine(fill, Offset(0f, y), Offset(x, y), strokeWidth = line, cap = StrokeCap.Round)
                if (knob) drawCircle(fill, radius = m.knob.toPx() / 2f, center = Offset(x, y))
            }
        }
        TimeText(clock(length), m, "now_playing_length", end = true)
    }
}

@Composable
private fun TimeText(text: String, m: DockMeasures, tag: String, end: Boolean = false) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = MonoFontFamily,
        fontSize = m.time,
        maxLines = 1,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = Modifier.widthIn(min = 27.dp).testTag(tag),
    )
}

/**
 * The collapsed sidebar's player (#118, design A): a rail tile above the downloads' ring while something plays — our
 * ring filling with the time played, ❚❚ or ▶ in it. It opens the player beside the rail. Nothing otherwise.
 */
@Composable
fun AudioRailIcon(modifier: Modifier = Modifier) {
    val player = LocalAudioPlayer.current ?: return
    val state by player.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val now = state
    if (now == null) {
        if (open) SideEffect { open = false }
        return
    }
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { BesideTheTile(gap = 6.dp.roundToPx(), drop = 4.dp.roundToPx(), margin = 8.dp.roundToPx()) }
    }
    val fraction = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.padding(bottom = 4.dp)) {
        RailTile(onClick = { open = !open }, tag = "rail_audio", label = "Now playing") {
            Box(
                Modifier
                    .size(RingTokens.RailTile)
                    .background(if (open) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
                    .semantics {
                        contentDescription = "Now playing: ${now.item.title}"
                        stateDescription = if (now.stopOnly) "reading" else if (now.playing) "playing" else "paused"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                PlayerRing(fraction, now.playing, voice = now.stopOnly)
            }
        }
        if (open) {
            ModalHost {}
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
            ) {
                Surface(
                    modifier = Modifier
                        .width(DockTokens.PopoverWidth)
                        .testTag("now_playing_popover")
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                open = false
                                true
                            } else {
                                false
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = cs.surfaceContainerHigh,
                    contentColor = cs.onSurface,
                    border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.85f)),
                    shadowElevation = 12.dp,
                ) {
                    NowPlayingPart(player, now, Modifier.padding(6.dp))
                }
            }
        }
    }
}

/** Our ring for the player: the time played as a teal arc on a faint track, the play/pause it also is in it. */
@Composable
internal fun PlayerRing(fraction: Float, playing: Boolean, modifier: Modifier = Modifier, voice: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(RingTokens.Ring), contentAlignment = Alignment.Center) {
        ProgressRing(fraction.takeIf { it > 0f })
        Icon(
            when {
                voice -> AudioGlyphs.Voice
                playing -> AudioGlyphs.Pause
                else -> AudioGlyphs.Play
            },
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(RingTokens.Glyph),
        )
    }
}

/**
 * A voice note's ▶ or ❚❚ in its card's ring place (#118): teal, in a finger's 44 dp on touch and the bar's 32 dp
 * under a pointer, as the download ring it stands in for.
 */
@Composable
internal fun AudioToggleButton(playing: Boolean, tag: String, onClick: () -> Unit) {
    val touch = !LocalPointerAvailable.current
    QuietButton(
        size = if (touch) 44.dp else 32.dp,
        corner = 8.dp,
        label = if (playing) "Pause" else "Play",
        tag = tag,
        onClick = onClick,
    ) {
        Icon(
            if (playing) AudioGlyphs.Pause else AudioGlyphs.Play,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A tap square with no ripple, washed under the pointer, as the bar's icons. [width] null wraps its content. */
@Composable
private fun QuietButton(
    size: Dp,
    corner: Dp,
    label: String,
    tag: String,
    onClick: () -> Unit,
    width: Dp? = size,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .height(size)
            .then(if (width != null) Modifier.width(width) else Modifier)
            .clip(RoundedCornerShape(corner))
            .background(if (hovered) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 1×, 1.5×, 2×. */
internal fun speedText(speed: Float): String {
    val tenths = kotlin.math.round(speed * 10).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10}.${tenths % 10}×"
}

/** m:ss, as the prototype's times. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The player's line glyphs: #91's rule (a 24 grid, round ends, no fills), the prototype's paths. */
internal object AudioGlyphs {
    /** ▶. */
    val Play: ImageVector by lazy {
        glyph("play", stroke = 1.8f) {
            moveTo(8f, 5.5f)
            lineTo(18.5f, 12f)
            lineTo(8f, 18.5f)
            close()
        }
    }

    /** ❚❚: two thin bars. */
    val Pause: ImageVector by lazy {
        glyph("pause", stroke = 2f) {
            moveTo(9f, 6f)
            lineTo(9f, 18f)
            moveTo(15f, 6f)
            lineTo(15f, 18f)
        }
    }

    /** A voice reading: a small speaker and one wave. */
    val Voice: ImageVector by lazy {
        glyph("voice", stroke = 1.7f) {
            moveTo(5f, 10f)
            lineTo(8f, 10f)
            lineTo(12f, 6.5f)
            lineTo(12f, 17.5f)
            lineTo(8f, 14f)
            lineTo(5f, 14f)
            close()
            moveTo(15.5f, 9f)
            curveTo(17f, 10.5f, 17f, 13.5f, 15.5f, 15f)
        }
    }

    /** ✕. */
    val Stop: ImageVector by lazy {
        glyph("stop", stroke = 1.7f) {
            moveTo(6.5f, 6.5f)
            lineTo(17.5f, 17.5f)
            moveTo(17.5f, 6.5f)
            lineTo(6.5f, 17.5f)
        }
    }

    private fun glyph(name: String, stroke: Float, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "audio.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            ).build()
}

/** The prototype's dock, under a pointer and on touch (`.np`, `.phone .np`). */
private class DockMeasures(
    val padding: androidx.compose.foundation.layout.PaddingValues,
    val gap: Dp,
    val button: Dp,
    val corner: Dp,
    val glyph: Dp,
    val title: androidx.compose.ui.unit.TextUnit,
    val chat: androidx.compose.ui.unit.TextUnit,
    val speed: androidx.compose.ui.unit.TextUnit,
    val speedHeight: Dp,
    val speedPadding: Dp,
    val stop: Dp,
    val stopGlyph: Dp,
    val seekRow: Dp,
    val seekStart: Dp,
    val seekEnd: Dp,
    val time: androidx.compose.ui.unit.TextUnit,
    val knob: Dp,
    val knobAlways: Boolean,
)

private object DockTokens {
    val Pointer = DockMeasures(
        padding = androidx.compose.foundation.layout.PaddingValues(start = 2.dp, top = 6.dp, end = 4.dp, bottom = 0.dp),
        gap = 6.dp,
        button = 28.dp,
        corner = 8.dp,
        glyph = 15.dp,
        title = 13.sp,
        chat = 12.sp,
        speed = 12.sp,
        speedHeight = 24.dp,
        speedPadding = 5.dp,
        stop = 24.dp,
        stopGlyph = 13.dp,
        seekRow = 20.dp,
        seekStart = 34.dp,
        seekEnd = 6.dp,
        time = 11.sp,
        knob = 10.dp,
        knobAlways = false,
    )
    val Touch = DockMeasures(
        padding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, top = 8.dp, end = 6.dp, bottom = 0.dp),
        gap = 4.dp,
        button = 44.dp,
        corner = 10.dp,
        glyph = 19.dp,
        title = 15.sp,
        chat = 13.5.sp,
        speed = 14.sp,
        speedHeight = 44.dp,
        speedPadding = 9.dp,
        stop = 44.dp,
        stopGlyph = 16.dp,
        seekRow = 30.dp,
        seekStart = 48.dp,
        seekEnd = 12.dp,
        time = 12.sp,
        knob = 9.dp,
        knobAlways = true,
    )

    /** The scrubber's hairline. */
    val Track = 2.dp

    /** The rail's popover. */
    val PopoverWidth = 340.dp
}
