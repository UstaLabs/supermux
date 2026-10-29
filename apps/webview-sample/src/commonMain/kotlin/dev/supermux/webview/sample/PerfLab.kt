package dev.supermux.webview.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebView
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How many messages the perf lab shows unless told otherwise: the 200-message chat of spec §10. */
const val DEFAULT_VIEWS = 200

/** The message counts the lab offers. */
val VIEW_COUNTS: List<Int> = listOf(50, DEFAULT_VIEWS, 500, 1_000)

/** The tallest an embed grows: its WrapContentHeight cap. A taller page scrolls inside it. */
val MAX_EMBED_HEIGHT = 360.dp

/** How the perf lab lays its messages out. */
enum class ListLayout(val label: String) {
    /** Only what is on screen is composed: a message that scrolls away is disposed, and comes back as a new view. */
    Lazy("LazyColumn"),

    /** Every message composed at once, hundreds of cheap handles: a hidden view keeps its engine for the grace. */
    Column("Column"),
}

/** The perf lab's scripted runs. Each prints a `perf-lab` line and shows its report. */
enum class LabRun(val label: String) {
    /** From the top to the end and back without stopping, once the first screen has gone live. */
    Scroll("Scroll test"),

    /** Down a screen at a time, resting on each so its pages go live, then back up without stopping. */
    Reading("Reading test"),

    /** One fling from where the list is, as a finger throws it. */
    Fling("Fling"),
}

/** Where a run is. */
enum class RunPhase(val label: String) {
    Resting("resting"),
    Down("down"),
    Up("back up"),
    Flinging("flinging"),
    ;

    /** Whether the list moves: every phase but [Resting]. */
    val moving: Boolean get() = this != Resting
}

/**
 * The perf lab: its settings, its list's scroll position, and the scripted runs that measure the runtime (spec §10).
 * The numbers come from [runtime] and from [engines], which every engine the runtime creates passes through.
 *
 * Main thread only.
 */
@Stable
class PerfLabState(val engines: EngineCounter, val runtime: WebViewRuntime) {
    init {
        require(runtime.factory === engines) { "The runtime must create its engines through the counter, or churn goes uncounted" }
    }

    /** How many messages the list shows. */
    var views: Int by mutableIntStateOf(DEFAULT_VIEWS)

    var layout: ListLayout by mutableStateOf(ListLayout.Lazy)

    /** Whether messages embed a still image instead of a web view: the baseline the web views are measured against. */
    var baseline: Boolean by mutableStateOf(false)

    /** How long the reading test rests on each screen: long enough for its pages to go live and be snapshotted. */
    var readMillis: Long = 1_000

    /** How fast the scroll test, and every run's way back up, moves, in dp per second. */
    var speedDp: Float = 3_000f

    val lazyList: LazyListState = LazyListState()
    val column: ScrollState = ScrollState(0)

    /** The list area's height in px, as last laid out: a screen. */
    internal var viewportPx: Int = 0

    /** The run asked for, which [PerfLab] starts. */
    internal var requested: LabRun? by mutableStateOf(null)

    /** The run under way. */
    var running: LabRun? by mutableStateOf(null)
        private set

    var phase: RunPhase? by mutableStateOf(null)
        private set

    var lastReport: RunReport? by mutableStateOf(null)
        private set

    /** A still the size of the tallest embed, for the baseline. */
    internal val still: ImageBitmap by lazy { stillImage() }

    private val demos = ArrayList<Demo>()

    /** Starts [run] in the [PerfLab] showing this, unless one is under way. */
    fun start(run: LabRun) {
        if (running == null && requested == null) requested = run
    }

    /** The [index]th message, made once. */
    fun demo(index: Int): Demo {
        while (demos.size <= index) demos += demoAt(demos.size)
        return demos[index]
    }

    /** The numbers now. Cheap: every one is a field read. */
    fun sample(): LabStats = LabStats(
        live = runtime.pool.liveCount,
        budget = runtime.pool.budget,
        maxLive = runtime.pool.maxLive,
        spares = runtime.pool.idleCount,
        engines = engines.existing,
        created = engines.created,
        destroyed = engines.destroyed,
        loads = engines.loads,
        snapshots = engines.snapshots,
        cacheBytes = runtime.snapshots.sizeBytes,
        cacheCount = runtime.snapshots.count,
        cacheMaxBytes = runtime.snapshots.maxBytes,
        coverBytes = runtime.coverBytes,
        coverCount = runtime.coverCount,
        pages = runtime.pages.count,
        pageBytes = runtime.pages.sizeBytes,
    )

    /** What the OS says when memory is short: the runtime trims its snapshot cache, drops its spares, halves its budget. */
    fun memoryPressure() = runtime.onMemoryPressure()

    fun memoryRecovered() = runtime.onMemoryRecovered()

    internal suspend fun execute(run: LabRun, density: Float, flingBehavior: FlingBehavior) = when (run) {
        LabRun.Scroll -> scrollTest(density)
        LabRun.Reading -> readingTest(density)
        LabRun.Fling -> flingOnce(flingBehavior, density)
    }

    private val scrollable: ScrollableState
        get() = when (layout) {
            ListLayout.Lazy -> lazyList
            ListLayout.Column -> column
        }

    private suspend fun scrollTest(density: Float) = measure(LabRun.Scroll) { recorder ->
        toTop()
        rest(recorder)
        phase = RunPhase.Down
        glide(recorder, forward = true, pxPerSecond = speedDp * density)
        phase = RunPhase.Up
        glide(recorder, forward = false, pxPerSecond = speedDp * density)
    }

    private suspend fun readingTest(density: Float) = measure(LabRun.Reading) { recorder ->
        toTop()
        rest(recorder)
        val screen = max(viewportPx * READ_STEP, MIN_READ_STEP_DP * density)
        while (scrollable.canScrollForward) {
            phase = RunPhase.Down
            if (glide(recorder, forward = true, pxPerSecond = READ_SPEED_DP * density, limitPx = screen) < 1f) break
            rest(recorder)
        }
        phase = RunPhase.Up
        glide(recorder, forward = false, pxPerSecond = speedDp * density)
    }

    private suspend fun flingOnce(behavior: FlingBehavior, density: Float) = measure(LabRun.Fling) { recorder ->
        val target = scrollable
        val velocity = FLING_DP * density * if (target.canScrollForward) 1f else -1f
        phase = RunPhase.Flinging
        coroutineScope {
            // The fling animates itself; this records the frames it takes.
            val meter = launch {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    recorder.frames.add(now - last)
                    last = now
                    recorder.sample(sample())
                }
            }
            try {
                target.scroll { with(behavior) { performFling(velocity) } }
            } finally {
                meter.cancel()
            }
        }
    }

    /** Runs [body] as [run]: one at a time, and reported however it ends. */
    private suspend fun measure(run: LabRun, body: suspend (RunRecorder) -> Unit) {
        val setup = "${layout.label} · $views ${if (baseline) "images" else "web views"}"
        val recorder = RunRecorder(sample())
        engines.resetPeak()
        running = run
        var finished = false
        try {
            body(recorder)
            finished = true
        } finally {
            val report = recorder.report(
                name = if (finished) run.label else "${run.label} (stopped)",
                setup = setup,
                end = sample(),
                peakEngines = engines.peakEngines,
                maxEngines = runtime.config.maxLive + runtime.config.maxIdle,
            )
            lastReport = report
            println(report.line())
            running = null
            phase = null
        }
    }

    private suspend fun toTop() = when (layout) {
        ListLayout.Lazy -> lazyList.scrollToItem(0)
        ListLayout.Column -> {
            column.scrollTo(0)
            Unit
        }
    }

    /** Stays still for [readMillis], as a reader does, sampling as the pages go live. It asks for no frames. */
    private suspend fun rest(recorder: RunRecorder) {
        phase = RunPhase.Resting
        var left = readMillis
        while (left > 0) {
            val step = min(left, REST_SAMPLE_MILLIS)
            delay(step)
            left -= step
            recorder.sample(sample())
        }
    }

    /**
     * Scrolls a frame at a time at [pxPerSecond] until [limitPx] or the end, recording each frame's interval. Returns
     * how far it went.
     */
    private suspend fun glide(
        recorder: RunRecorder,
        forward: Boolean,
        pxPerSecond: Float,
        limitPx: Float = Float.POSITIVE_INFINITY,
    ): Float {
        val target = scrollable
        var moved = 0f
        var stalled = 0
        var last = withFrameNanos { it }
        while (moved < limitPx && stalled < MAX_STALLED_FRAMES && (if (forward) target.canScrollForward else target.canScrollBackward)) {
            val now = withFrameNanos { it }
            val elapsed = now - last
            last = now
            recorder.frames.add(elapsed)
            val step = min(pxPerSecond * elapsed / 1e9f, limitPx - moved)
            val consumed = abs(target.scrollBy(if (forward) step else -step))
            moved += consumed
            stalled = if (consumed > 0f) 0 else stalled + 1
            recorder.sample(sample())
        }
        return moved
    }

    private companion object {
        /** A reader's step: most of a screen, so each message is seen at rest at least once. */
        const val READ_STEP = 0.8f
        const val MIN_READ_STEP_DP = 100f
        const val READ_SPEED_DP = 2_000f
        const val FLING_DP = 8_000f
        const val REST_SAMPLE_MILLIS = 50L
        const val MAX_STALLED_FRAMES = 30
    }
}

/**
 * The perf lab screen (spec §10): a chat of [PerfLabState.views] messages, each embedding a web view that goes live
 * when it is on screen and still (`Activation.WhenVisible`) and wraps its page's height. Above it, the controls, the
 * runtime's numbers and the last run's report. It provides the lab's runtime to its web views.
 */
@Composable
fun PerfLab(lab: PerfLabState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val fling = ScrollableDefaults.flingBehavior()
    val request = lab.requested
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        try {
            lab.execute(request, density, fling)
        } finally {
            lab.requested = null
        }
    }
    ProvideWebViewRuntime(lab.runtime) {
        Column(modifier.fillMaxSize()) {
            LabControls(lab)
            LabNumbers(lab)
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { lab.viewportPx = it.height }.testTag(SampleTags.Viewport)) {
                LabList(lab)
            }
        }
    }
}

@Composable
private fun LabControls(lab: PerfLabState) {
    val idle = lab.running == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListLayout.entries.forEach { layout ->
                Choice(layout.label, lab.layout == layout, idle, SampleTags.layout(layout)) { lab.layout = layout }
            }
            Spacer(Modifier.width(8.dp))
            Choice("Web views", !lab.baseline, idle, SampleTags.ContentWeb) { lab.baseline = false }
            Choice("Images", lab.baseline, idle, SampleTags.ContentImages) { lab.baseline = true }
            Spacer(Modifier.width(8.dp))
            VIEW_COUNTS.forEach { count -> Choice("$count", lab.views == count, idle, SampleTags.views(count)) { lab.views = count } }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabRun.entries.forEach { run ->
                Button(onClick = { lab.start(run) }, enabled = idle, modifier = Modifier.testTag(SampleTags.run(run))) { Text(run.label) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = lab::memoryPressure, modifier = Modifier.testTag(SampleTags.Pressure)) { Text("Memory pressure") }
            OutlinedButton(onClick = lab::memoryRecovered, modifier = Modifier.testTag(SampleTags.Recovered)) { Text("Memory recovered") }
            val running = lab.running
            if (running != null) {
                Text("${running.label}: ${lab.phase?.label.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, enabled = enabled, modifier = Modifier.testTag(tag))
}

/** The runtime's numbers, read four times a second, and the last run's report. */
@Composable
private fun LabNumbers(lab: PerfLabState) {
    val stats by produceState(lab.sample(), lab) {
        while (true) {
            delay(NUMBERS_REFRESH_MILLIS)
            value = lab.sample()
        }
    }
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stats.enginesLine(), Modifier.testTag(SampleTags.StatsEngines), style = style)
        Text(stats.memoryLine(), Modifier.testTag(SampleTags.StatsMemory), style = style)
        Text(
            lab.lastReport?.summary()?.let { "last run " + it } ?: NO_RUN_YET,
            Modifier.testTag(SampleTags.StatsRun),
            style = style,
        )
    }
}

@Composable
private fun LabList(lab: PerfLabState) {
    val views = lab.views
    val baseline = lab.baseline
    when (lab.layout) {
        ListLayout.Lazy -> LazyColumn(
            state = lab.lazyList,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(views, key = { it }, contentType = { lab.demo(it).kind }) { index -> Message(lab.demo(index), baseline, lab.still) }
        }
        ListLayout.Column -> Column(
            Modifier.fillMaxSize().verticalScroll(lab.column).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (index in 0 until views) key(index) { Message(lab.demo(index), baseline, lab.still) }
        }
    }
}

/** One chat message: who and what, a line or three of text, and the embed. */
@Composable
private fun Message(demo: Demo, baseline: Boolean, still: ImageBitmap) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Agent · message ${demo.index + 1} · ${demo.kind.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(demo.text, style = MaterialTheme.typography.bodyMedium)
        val embed = Modifier.testTag(SampleTags.demo(demo.index))
        if (baseline) {
            Image(
                still,
                contentDescription = null,
                modifier = embed.fillMaxWidth().height(pageHeight(demo.content).dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            val state = rememberWebViewState(demo.content)
            WebView(state, embed, activation = EMBED_ACTIVATION, sizing = EMBED_SIZING)
        }
    }
}

/** Chat embeds go live once on screen and still, never mid-fling (spec §4). */
private val EMBED_ACTIVATION = Activation.WhenVisible()
private val EMBED_SIZING = WebSizing.WrapContentHeight(max = MAX_EMBED_HEIGHT)

private const val NUMBERS_REFRESH_MILLIS = 250L

private val STILL_COLOR = Color(0xFF4F7CFF)

private const val NO_RUN_YET =
    "last run   none yet. Scroll test: to the end and back without stopping. Reading test: a screen at a time, resting on each."

/** The baseline's still: a solid colour, as a fake page is, with some lines on it, at an embed's width and tallest height. */
private fun stillImage(): ImageBitmap {
    val width = 720
    val height = 360
    val image = ImageBitmap(width, height)
    val canvas = Canvas(image)
    val paint = Paint().apply { color = STILL_COLOR }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.color = Color.White.copy(alpha = 0.4f)
    for (line in 0 until 6) {
        val top = 36f + line * 48f
        canvas.drawRect(28f, top, 28f + 560f - line * 60f, top + 20f, paint)
    }
    return image
}
