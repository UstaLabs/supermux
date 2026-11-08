package dev.supermux.webview.sample.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.supermux.webview.WebViewState
import dev.supermux.webview.sample.PerfLabState
import dev.supermux.webview.sample.RunRecorder
import dev.supermux.webview.sample.RunReport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The chat's scripted runs, which measure its frames as the perf lab's runs do and print a `chat-perf:` line. */
enum class ChatRun(val label: String) {
    /** A screen at a time, resting on each so its previews go live, through the whole chat; then back without stopping. */
    Read("Reading the chat"),

    /** To the first message and back without stopping. */
    Scroll("Scrolling the chat"),

    /** Flings, as a thumb throws them: six toward the start, six back. */
    Fling("Flinging the chat"),
}

/**
 * The chat demo's state: its messages, newest last, the list's position, what the previews told the app over the
 * bridge, and the composer. It lives as long as the app (the screen can go and come back), on [lab]'s runtime, whose
 * numbers its runs report. Main thread only.
 * @param cacheWindow whether the list composes rows ahead of the screen (a viewport ahead, half one behind) instead of
 *   Compose's default prefetch of the next row: spreads a fling's composition over the frames' idle time.
 * @param probe what the chat's layout does as it moves, with the engines' calls (`-chat-probe`): its runs print it.
 */
@Stable
@OptIn(ExperimentalFoundationApi::class)
class ChatState(val lab: PerfLabState, val cacheWindow: Boolean = false, val probe: ChatProbe? = null) {
    init {
        if (probe != null) lab.engines.trace = probe::event
    }

    /** Every row, oldest first. The list shows them newest at the bottom ([itemAt]). */
    val items = mutableStateListOf<ChatItem>().apply { addAll(chatScript()) }

    /** The list's position: index 0 is the newest message, at the bottom. */
    val list: LazyListState =
        if (cacheWindow) LazyListState(LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f)) else LazyListState()

    val snackbars: SnackbarHostState = SnackbarHostState()

    private val noticeChannel = Channel<String>(Channel.CONFLATED)

    /** What the app says, newest only: the screen shows each as a snackbar, replacing the one before. */
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    /** The composer's text. */
    var draft: String by mutableStateOf("")

    /**
     * Whether the previews show as still pictures (their placeholders) instead of web views: the baseline the chat's
     * runs measure the web views against, as the perf lab's still images are.
     */
    var baseline: Boolean by mutableStateOf(false)

    /** The count the counter previews show: the app keeps it, and they ask for it over the bridge. */
    var count: Int by mutableIntStateOf(0)
        private set

    /** The colour each mixer last told the app, by preview slug, as `#rrggbb`. */
    val colours = mutableStateMapOf<String, String>()

    /** The best Breakout score the app heard of. */
    var best: Int by mutableIntStateOf(0)
        private set

    /** The run asked for, which the screen starts. */
    var requested: ChatRun? by mutableStateOf(null)

    var running: ChatRun? by mutableStateOf(null)
        private set

    var lastReport: RunReport? by mutableStateOf(null)
        private set

    private var made = 0

    /** The row at list index [index]: 0 is the newest. */
    fun itemAt(index: Int): ChatItem = items[items.size - 1 - index]

    val previewCount: Int get() = items.sumOf { (it as? AgentMessage)?.previews?.size ?: 0 }

    val messageCount: Int get() = items.count { it is UserMessage || it is AgentMessage }

    fun notify(text: String) {
        noticeChannel.trySend(text)
    }

    /** Starts [run] in the chat screen, unless one is under way. */
    fun start(run: ChatRun) {
        if (running == null && requested == null) requested = run
    }

    /**
     * Sends [text] as the user's message, and answers it as the agent does: a moment of typing, then a new preview of
     * what the text asks for (a fresh page: a new content, so it never shares a snapshot or a parked page with the one
     * up the chat).
     */
    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val n = ++made
        items += UserMessage("sent-$n", clean, "now")
        val typing = TypingIndicator("typing-$n")
        items += typing
        list.animateScrollToItem(0)
        delay(TYPING_MILLIS)
        val (preview, reply) = answer(clean, n)
        items.remove(typing)
        items += AgentMessage("reply-$n", reply, "now", listOf(preview.again("${preview.slug}-again-$n")))
        list.animateScrollToItem(0)
    }

    private fun answer(text: String, n: Int): Pair<PreviewSpec, String> {
        val lower = text.lowercase()
        val asked = KEYWORDS.firstOrNull { (words, _) -> words.any { it in lower } }?.second
        return if (asked != null) {
            asked to "Here's a fresh one: ${asked.title.replaceFirstChar { it.lowercase() }}."
        } else {
            val surprise = SURPRISES[(n - 1) % SURPRISES.size]
            surprise to "Not sure what you meant, so here's something I like: ${surprise.title.replaceFirstChar { it.lowercase() }}."
        }
    }

    /**
     * Registers what [spec]'s page tells the app on [state]'s bridge, and returns what undoes it. Registered on the state,
     * the handlers serve every engine that shows the page, a parked page adopted again included.
     */
    fun bind(spec: PreviewSpec, state: WebViewState): () -> Unit {
        val bridge = state.bridge
        val undo = ArrayList<() -> Unit>()
        when (spec.bridge) {
            PreviewBridge.Counter -> {
                undo += bridge.handle("counter.get") { JsonPrimitive(count) }
                undo += bridge.handle("counter.add") { params ->
                    count += params.int("delta") ?: 0
                    notify("The page asked, the app counted: $count")
                    JsonPrimitive(count)
                }
            }
            PreviewBridge.Colour -> {
                undo += bridge.on("colour.live") { data -> data.text("hex")?.let { colours[spec.slug] = it } }
                undo += bridge.on("colour.pick") { data ->
                    val hex = data.text("hex") ?: return@on
                    colours[spec.slug] = hex
                    val name = data.text("name")
                    notify("The page sent the app ${name?.let { "$it, " }.orEmpty()}$hex")
                }
            }
            PreviewBridge.Game -> {
                undo += bridge.on("game.level") { data ->
                    notify("Level ${data.int("level") ?: 1} cleared · ${data.int("score") ?: 0} points")
                }
                undo += bridge.on("game.over") { data ->
                    val score = data.int("score") ?: 0
                    best = max(best, score)
                    notify("Game over: $score points. Best so far: $best")
                }
            }
            PreviewBridge.Form -> undo += bridge.on("form.submit") { data ->
                val name = data.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "there"
                val topic = data.text("topic")?.takeIf { it.isNotEmpty() }
                notify("Thanks, $name! The app got your note${topic?.let { " about ${it.lowercase()}" }.orEmpty()}.")
            }
            PreviewBridge.Code -> undo += bridge.on("code.copy") { data ->
                notify("The page copied ${data.int("lines") ?: 0} lines")
            }
            PreviewBridge.None -> Unit
        }
        return { undo.forEach { it() } }
    }

    /**
     * Runs [run] over the list, recording the interval of every frame the list moves in, and the runtime's peaks, as the
     * perf lab's runs do. Prints a `chat-perf:` line and tells the user.
     */
    internal suspend fun execute(run: ChatRun, density: Float, fling: FlingBehavior) {
        val recorder = RunRecorder(lab.sample())
        lab.engines.resetPeak()
        running = run
        var finished = false
        try {
            list.scrollToItem(0)
            rest(recorder, SETTLE_MILLIS)
            probe?.begin(run.label)
            when (run) {
                ChatRun.Read -> {
                    val screen = max(list.layoutInfo.viewportSize.height * READ_STEP, MIN_STEP_DP * density)
                    while (list.canScrollForward) {
                        if (glide(recorder, forward = true, pxPerSecond = READ_SPEED_DP * density, limitPx = screen) < 1f) break
                        rest(recorder, READ_MILLIS)
                    }
                    glide(recorder, forward = false, pxPerSecond = SCROLL_SPEED_DP * density)
                }
                ChatRun.Scroll -> {
                    glide(recorder, forward = true, pxPerSecond = SCROLL_SPEED_DP * density)
                    glide(recorder, forward = false, pxPerSecond = SCROLL_SPEED_DP * density)
                }
                ChatRun.Fling -> repeat(FLINGS * 2) { i ->
                    flingOnce(recorder, fling, FLING_DP * density * if (i < FLINGS) 1f else -1f)
                    rest(recorder, FLING_REST_MILLIS)
                }
            }
            finished = true
        } finally {
            probe?.end()
            val report = recorder.report(
                name = if (finished) run.label else "${run.label} (stopped)",
                setup = "chat · $previewCount previews in $messageCount messages, ${if (baseline) "as still pictures" else "as web views"}" +
                    if (cacheWindow) ", cache window" else "",
                end = lab.sample(),
                peakEngines = lab.engines.peakEngines,
                maxEngines = lab.runtime.config.maxLive + lab.runtime.config.maxIdle,
            )
            lastReport = report
            println("chat-perf: ${report.summary()}")
            running = null
            report.frames?.let { notify("${run.label}: ${it.frames} frames, ${it.percentiles()}, ${it.dropped} dropped") }
        }
    }

    /** Stays still for [millis], sampling the runtime as the previews on screen go live. Asks for no frames. */
    private suspend fun rest(recorder: RunRecorder, millis: Long) {
        var left = millis
        while (left > 0) {
            val step = min(left, SAMPLE_MILLIS)
            delay(step)
            left -= step
            recorder.sample(lab.sample())
        }
    }

    /** Scrolls a frame at a time at [pxPerSecond] until [limitPx] or the end, recording each frame. Returns how far. */
    private suspend fun glide(recorder: RunRecorder, forward: Boolean, pxPerSecond: Float, limitPx: Float = Float.POSITIVE_INFINITY): Float {
        var moved = 0f
        var stalled = 0
        var last = withFrameNanos { it }
        while (moved < limitPx && stalled < MAX_STALLED && (if (forward) list.canScrollForward else list.canScrollBackward)) {
            val now = withFrameNanos { it }
            val elapsed = now - last
            last = now
            recorder.frames.add(elapsed)
            val step = min(pxPerSecond * elapsed / 1e9f, limitPx - moved)
            val consumed = abs(list.scrollBy(if (forward) step else -step))
            moved += consumed
            stalled = if (consumed > 0f) 0 else stalled + 1
            recorder.sample(lab.sample())
        }
        return moved
    }

    /** One fling at [velocity] px/s with the platform's decay, recording the frames it takes. */
    private suspend fun flingOnce(recorder: RunRecorder, behavior: FlingBehavior, velocity: Float) = coroutineScope {
        val meter = launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                recorder.frames.add(now - last)
                last = now
                recorder.sample(lab.sample())
            }
        }
        try {
            list.scroll { with(behavior) { performFling(velocity) } }
        } finally {
            meter.cancel()
        }
    }

    private companion object {
        const val TYPING_MILLIS = 900L
        const val SETTLE_MILLIS = 1_500L
        const val READ_MILLIS = 1_200L
        const val READ_STEP = 0.8f
        const val MIN_STEP_DP = 120f
        const val READ_SPEED_DP = 2_000f
        const val SCROLL_SPEED_DP = 3_000f
        const val FLING_DP = 7_000f
        const val FLINGS = 6
        const val FLING_REST_MILLIS = 700L
        const val SAMPLE_MILLIS = 50L
        const val MAX_STALLED = 30

        /** What the composer's words ask for. The first match wins. */
        val KEYWORDS: List<Pair<List<String>, PreviewSpec>> = listOf(
            listOf("galax", "space", "star") to Previews.galaxyClassic,
            listOf("knot", "metal", "torus") to Previews.knotGold,
            listOf("terrain", "landscape", "mountain", "fly") to Previews.terrainMeadow,
            listOf("planet", "rotate", "spin", "orbit") to Previews.orbitPlanet,
            listOf("crystal", "gem") to Previews.orbitCrystal,
            listOf("cube", "wave", "instanc") to Previews.waveNeon,
            listOf("hero", "landing", "gradient") to Previews.heroAurora,
            listOf("loader", "loading", "spinner") to Previews.loadersViolet,
            listOf("boarding", "flight", "ticket") to Previews.flipPass,
            listOf("flip", "credit") to Previews.flipCard,
            listOf("kinetic", "typograph", "letter") to Previews.typeBold,
            listOf("flow", "generative", "particle", "art") to Previews.flowNeon,
            listOf("life", "conway", "cell") to Previews.lifeMint,
            listOf("firework") to Previews.fireworksNight,
            listOf("confetti", "party", "celebrat") to Previews.confetti,
            listOf("voice", "audio", "sound", "record") to Previews.voiceNote,
            listOf("bar", "chart", "users") to Previews.barsUsers,
            listOf("line", "latency", "graph", "trend") to Previews.lineLatency,
            listOf("ring", "progress", "goal") to Previews.ringsActivity,
            listOf("blob", "organic", "morph") to Previews.blobCandy,
            listOf("clock", "time") to Previews.clockClassic,
            listOf("weather", "rain", "sunny") to Previews.weatherSunny,
            listOf("count", "bridge") to Previews.counter,
            listOf("colour", "color", "slider", "palette") to Previews.mixer,
            listOf("game", "play", "breakout") to Previews.breakout,
            listOf("form", "input", "typing") to Previews.form,
            listOf("code", "kotlin") to Previews.codeKotlin,
            listOf("markdown", "notes", "docs") to Previews.notesMarkdown,
        )

        /** What the agent makes when it can't tell what was asked for, in turn. */
        val SURPRISES: List<PreviewSpec> = listOf(
            Previews.galaxyRose, Previews.fireworksCity, Previews.flowEmber, Previews.knotNeon, Previews.waveSunset,
            Previews.heroSunrise, Previews.terrainDusk, Previews.lifeAmber,
        )
    }
}

private fun JsonElement.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let {
    it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt()
}
