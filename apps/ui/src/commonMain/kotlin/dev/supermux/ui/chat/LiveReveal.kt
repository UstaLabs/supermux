package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import dev.supermux.chat.RevealClock
import dev.supermux.ui.MdBlock
import dev.supermux.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.first
import kotlin.math.max

/**
 * Card #76 (B3, the ink edge): a reply's reveal while it is written. The [RevealClock] runs once per frame only while
 * there is something to move or dry. Composition reads [laidOut], which changes a word at a time. The draw pass reads
 * the frame, so the edge redraws each frame without recomposing anything.
 */
@Stable
class LiveRevealState {
    internal val clock = RevealClock()

    /** How much of the reply's text to lay out. */
    var laidOut by mutableIntStateOf(0)
        private set

    /** The stream has ended and its reveal is complete: the row may draw statically. */
    var finished by mutableStateOf(false)
        private set

    /** The frame the edge was last drawn for. */
    private var frameMs by mutableLongStateOf(0L)
    private var text by mutableStateOf("")
    private var done by mutableStateOf(false)
    private var reduced by mutableStateOf(false)

    /** Some laid-out text is still inking in (false under reduced motion and once dry). */
    val masking: Boolean get() = frameMs >= 0 && !reduced && clock.firstUnsettled() < clock.laidOut

    internal fun offer(text: String, done: Boolean, reduced: Boolean) {
        this.text = text
        this.done = done
        this.reduced = reduced
    }

    /** Sleeps until there is new text (or the end), then runs the clock a frame at a time until it settles. */
    internal suspend fun run() {
        while (true) {
            snapshotFlow { text.length > clock.laidOut || (done && !finished) }.first { it }
            do {
                val now = withFrameMillis { it }
                clock.advance(now.toDouble(), text, done, reduced)
                laidOut = clock.laidOut
                frameMs = now
            } while (clock.animating)
            if (done) finished = true
        }
    }

    /** Whether a block whose raw text ends at [sourceEnd] has characters still inking in (read in a layer block). */
    internal fun masks(sourceEnd: Int): Boolean = frameMs >= 0 && !reduced && clock.firstUnsettled() < sourceEnd

    /**
     * Fades the newest characters of one text block: per line, a horizontal gradient of each character's opacity,
     * applied with [BlendMode.DstIn] over what the text drew. A character's source index is counted back from
     * [sourceEnd], the end of the block's raw Markdown (markup after it shifts the edge by a character or two).
     */
    internal fun drawEdge(scope: DrawScope, layout: TextLayoutResult, sourceEnd: Int) {
        val now = frameMs.toDouble()
        if (reduced) return
        val n = layout.layoutInput.text.length
        val firstSource = sourceEnd - n
        val from = (clock.firstUnsettled() - firstSource).coerceIn(0, n)
        if (from >= n) return
        var line = layout.getLineForOffset(from)
        while (line < layout.lineCount) {
            val start = max(from, layout.getLineStart(line))
            val end = layout.getLineEnd(line, visibleEnd = true)
            if (start < end) {
                val left = layout.getBoundingBox(start).left
                val right = max(layout.getLineRight(line), left + 1f)
                val stops = (start until end).map { j ->
                    val box = layout.getBoundingBox(j)
                    val x = (((box.left + box.right) / 2 - left) / (right - left)).coerceIn(0f, 1f)
                    x to Color.Black.copy(alpha = clock.alphaAt(firstSource + j, now))
                }.sortedBy { it.first }
                val padded = listOf(0f to stops.first().second) + stops + listOf(1f to stops.last().second)
                val top = layout.getLineTop(line)
                scope.drawRect(
                    brush = Brush.horizontalGradient(*padded.toTypedArray(), startX = left, endX = right),
                    topLeft = Offset(left, top),
                    size = Size(right - left, layout.getLineBottom(line) - top),
                    blendMode = BlendMode.DstIn,
                )
            }
            line++
        }
    }
}

/** Feeds [state] this row's text and runs its clock while the row is composed. */
@Composable
internal fun RevealDriver(state: LiveRevealState, text: String, done: Boolean) {
    val reduced = LocalReduceMotion.current
    SideEffect { state.offer(text, done, reduced) }
    LaunchedEffect(state) { state.run() }
}

/** One chat's reveals by stream: the live row and the stored row that takes its place share one. */
@Stable
internal class LiveReveals {
    private val byStream = HashMap<String, LiveRevealState>()

    fun of(stream: String): LiveRevealState = byStream.getOrPut(stream) { LiveRevealState() }

    /** The reveal a stored reply continues: its stream's, while that has not finished. */
    fun unfinished(stream: String?): LiveRevealState? = stream?.let { byStream[it] }?.takeIf { !it.finished }
}

internal val LocalLiveReveals = staticCompositionLocalOf<LiveReveals?> { null }

/** The reveal a Markdown text block belongs to, and where its raw text ends in the revealed source. */
@Immutable
internal data class InkBlock(val reveal: LiveRevealState, val sourceEnd: Int)

internal val LocalInkBlock = compositionLocalOf<InkBlock?> { null }

/**
 * The ink edge over one text block. The offscreen layer that [BlendMode.DstIn] needs exists only while the block
 * has characters inking in.
 */
internal fun Modifier.inkEdge(block: InkBlock, layout: () -> TextLayoutResult?): Modifier =
    graphicsLayer {
        compositingStrategy = if (block.reveal.masks(block.sourceEnd)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        val l = layout() ?: return@drawWithContent
        block.reveal.drawEdge(this, l, block.sourceEnd)
    }

/**
 * Where each text block's raw Markdown ends in [source], found from the end (where a reveal is). Null for blocks the
 * edge skips (code, tables, images, rules) and for text it cannot find (a quote spread over several `>` lines).
 */
internal fun blockSourceEnds(source: String, blocks: List<MdBlock>): List<Int?> {
    val ends = MutableList<Int?>(blocks.size) { null }
    var limit = source.length
    for (k in blocks.indices.reversed()) {
        val block = blocks[k]
        val raw = when (block) {
            is MdBlock.Prose -> block.text
            is MdBlock.Heading -> block.text
            is MdBlock.Bullet -> block.text
            is MdBlock.Numbered -> block.text
            is MdBlock.Quote -> block.text
            is MdBlock.Code -> block.code
            else -> null
        }?.trimEnd()
        if (raw.isNullOrEmpty()) continue
        val at = source.lastIndexOf(raw, limit - raw.length)
        if (at < 0) continue
        limit = at
        if (block !is MdBlock.Code) ends[k] = at + raw.length
    }
    return ends
}
