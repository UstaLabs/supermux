package dev.supermux.editor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorZoom
import dev.supermux.editor.compose.KeyPath
import dev.supermux.editor.compose.WebKeyboard
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.editor.core.Transaction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.syntax.LanguageRegistry
import dev.supermux.editor.syntax.Syntax
import dev.supermux.editor.syntax.SyntaxBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** True once the state carries syntax colours (a `Mark` decoration). */
fun isColoured(state: EditorState): Boolean = state.facet(decorationsFacet).any { set -> set.any { it.value is Decoration.Mark } }

/**
 * The sample: pick a file, toggle wrapping and the theme, watch the frame times.
 *
 * @param bench non-null: open the 10k-line file, run the in-app benchmark (keystrokes, then wheel
 *   scrolling) and hand its JSON result here.
 * @param scrollDriver scrolls the window for the benchmark with real wheel input, one frame after
 *   another, and returns when done (the desktop posts wheel events; the web's driver sends trusted
 *   input over the DevTools protocol).
 * @param typeDriver clicks into the text and types 200 characters with real key events at a human
 *   pace, for the benchmark's key-event-to-paint latency.
 * @param onPhase startup milestones ("backend", "precompiled", "editor", "coloured") for the web's
 *   cold-start measurement.
 */
@Composable
fun SampleApp(
    loadBackend: suspend () -> SyntaxBackend,
    initialFile: SampleFile = SampleFile.KOTLIN,
    bench: ((String) -> Unit)? = null,
    scrollDriver: suspend () -> Unit = {},
    typeDriver: suspend () -> Unit = {},
    onPhase: (String) -> Unit = {},
) {
    val registry = LanguageRegistry.default
    var backend by remember { mutableStateOf<SyntaxBackend?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var texts by remember { mutableStateOf<Pair<String, String>?>(null) }
    var file by remember { mutableStateOf(if (bench != null) SampleFile.KOTLIN_10K else initialFile) }
    var dark by remember { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(EditorZoom.DEFAULT) }
    var webKeyboard by remember { mutableStateOf(WebKeyboard.AUTO) }
    val inputLog = remember { InputLog() }
    val stats = remember { FrameStats() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            texts = SampleFiles.kotlin() to SampleFiles.markdown()
            backend = loadBackend()
            onPhase("backend")
        } catch (e: Throwable) {
            failure = e.message ?: e.toString()
        }
    }
    // Each frame's start, for the overlay's frame work time.
    LaunchedEffect(stats) { while (true) withFrameNanos { stats.frameStart() } }

    // The open document: its queries compiled first (each in its own task), then its session.
    var loaded by remember { mutableStateOf<Pair<SampleFile, String>?>(null) }
    LaunchedEffect(file, backend, texts) {
        val b = backend ?: return@LaunchedEffect
        val (kotlin, markdown) = texts ?: return@LaunchedEffect
        file.language?.let { precompileSyntax(b, registry, it, onCompile = { q -> onPhase("query $q") }) { delay(1) } }
        onPhase("precompiled")
        loaded = file to SampleFiles.load(file, kotlin, markdown)
    }
    val session = loaded?.let { (f, text) ->
        remember(f, text, backend) { SampleSession(text, f.language, backend!!, registry, scope) { run -> scope.launch { run() } } }
    }
    if (session != null) {
        DisposableEffect(session) {
            val remove = session.view.addListener { if (it.docChanged || it.selectionSet) stats.changed() }
            onDispose { remove(); session.close() }
        }
        LaunchedEffect(session) {
            onPhase("editor")
            snapshotFlow { isColoured(session.view.state) }.first { it }
            withFrameNanos { }
            withFrameNanos { }
            onPhase("coloured")
            if (bench != null) bench(runBench(session, stats, scrollDriver, typeDriver))
        }
    }

    val font = packagedEditorFontFamily()
    val theme = remember(font, dark) { if (dark) EditorTheme.dark(font) else EditorTheme.light(font) }
    val chrome = if (dark) Color(0xFF151713) else Color(0xFFE9EAE4)
    val ink = if (dark) Color(0xFFD8DED3) else Color(0xFF1F221C)
    // The input log hears every transaction and (while it is shown) every key's path.
    if (session != null) {
        DisposableEffect(session, inputLog.enabled) {
            val view = session.view
            val remove = if (inputLog.enabled) view.addListener { inputLog.transaction(it) } else ({})
            view.onKeyPath = if (inputLog.enabled) { k, p -> inputLog.key(k, p) } else null
            onDispose { remove(); view.onKeyPath = null }
        }
        LaunchedEffect(session, webKeyboard) { session.view.webKeyboard = webKeyboard }
    }
    Column(Modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().background(chrome).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("settings", settings, ink) { settings = !settings }
            for (f in SampleFile.entries) Chip(f.label, f == file, ink) { file = f }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                failure != null -> Message("could not start: $failure", ink)
                session == null -> Message("loading…", ink)
                else -> SampleEditorPane(session, theme, wrap, stats, readOnly = readOnly, onFontSize = { fontSize = it })
            }
            val status = session?.let { s ->
                val st = s.view.state
                val syntax = when { s.language == null -> "plain"; Syntax.isOff(st) -> "syntax off"; else -> s.language }
                "${st.doc.lineCount} lines · $syntax · ${stats.summary}"
            } ?: ""
            BasicText(
                status,
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(chrome.copy(alpha = 0.9f)).padding(6.dp),
                style = TextStyle(color = ink, fontSize = 11.sp, fontFamily = font),
            )
            if (inputLog.enabled) {
                // Draws only: no pointer input, so the text under it stays tappable.
                BasicText(
                    inputLog.lines.joinToString("\n").ifEmpty { "input log: type something" },
                    Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(4.dp))
                        .background(chrome.copy(alpha = 0.85f)).padding(6.dp),
                    style = TextStyle(color = ink, fontSize = 10.sp, fontFamily = font),
                )
            }
        }
        if (settings) {
            SettingsSheet(
                chrome = chrome, ink = ink,
                wrap = wrap, onWrap = { wrap = it },
                dark = dark, onDark = { dark = it },
                readOnly = readOnly, onReadOnly = { readOnly = it },
                fontSize = session?.view?.effectiveFontSize ?: fontSize,
                onZoom = { step -> session?.view?.let { v -> if (step == 0) v.resetZoom() else v.zoomTo(v.effectiveFontSize + step) } },
                logOn = inputLog.enabled, onLog = { inputLog.enabled = it; inputLog.clear() },
                webKeyboard = webKeyboard, onWebKeyboard = { webKeyboard = it },
                onAddCursor = { session?.view?.let { addCursorBelow(it) } },
                onSingleCursor = { session?.view?.let { v -> v.dispatch(TransactionSpec(selection = EditorSelection.single(v.state.selection.main.anchor, v.state.selection.main.head), userEvent = "select")) } },
                onClose = { settings = false },
            )
        }
    }
}

/** The settings sheet: the editor's options and the device pass's debug tools. */
@Composable
private fun SettingsSheet(
    chrome: Color, ink: Color,
    wrap: Boolean, onWrap: (Boolean) -> Unit,
    dark: Boolean, onDark: (Boolean) -> Unit,
    readOnly: Boolean, onReadOnly: (Boolean) -> Unit,
    fontSize: Float, onZoom: (Int) -> Unit,
    logOn: Boolean, onLog: (Boolean) -> Unit,
    webKeyboard: WebKeyboard, onWebKeyboard: (WebKeyboard) -> Unit,
    onAddCursor: () -> Unit, onSingleCursor: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(chrome).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        @Composable
        fun Line(content: @Composable () -> Unit) = Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        Line {
            Chip(if (wrap) "wrap: on" else "wrap: off", wrap, ink) { onWrap(!wrap) }
            Chip(if (dark) "theme: dark" else "theme: light", false, ink) { onDark(!dark) }
            Chip(if (readOnly) "read-only: on" else "read-only: off", readOnly, ink) { onReadOnly(!readOnly) }
        }
        Line {
            BasicText("font ${FrameStats.fmt(fontSize.toDouble())}", style = TextStyle(color = ink, fontSize = 12.sp))
            Chip("A−", false, ink) { onZoom(-1) }
            Chip("A+", false, ink) { onZoom(1) }
            Chip("reset", false, ink) { onZoom(0) }
        }
        Line {
            Chip(if (logOn) "debug input log: on" else "debug input log: off", logOn, ink) { onLog(!logOn) }
            Chip("keys (web): ${webKeyboard.name.lowercase()}", webKeyboard != WebKeyboard.AUTO, ink) {
                onWebKeyboard(WebKeyboard.entries[(webKeyboard.ordinal + 1) % WebKeyboard.entries.size])
            }
        }
        Line {
            Chip("add cursor below", false, ink, onAddCursor)
            Chip("single cursor", false, ink, onSingleCursor)
            Chip("close", false, ink, onClose)
        }
    }
}

/**
 * The debug input log: each transaction's userEvent (with what it changed) and, while it is on,
 * the path every key took (`EditorView.onKeyPath`), newest last.
 */
@Stable
class InputLog(private val capacity: Int = 14) {
    var enabled: Boolean by mutableStateOf(false)
    val lines = mutableStateListOf<String>()

    fun clear() = lines.clear()

    private fun add(line: String) {
        lines += line
        while (lines.size > capacity) lines.removeAt(0)
    }

    fun transaction(tr: Transaction) {
        if (!tr.docChanged && !tr.selectionSet) return
        val event = tr.annotation(Transaction.userEvent) ?: "-"
        val join = if (tr.annotation(EditorAnnotations.imeJoinPrevious) == true) " (joins the previous)" else ""
        val what = if (tr.docChanged) {
            var ins = 0; var del = 0
            for (c in tr.changes.iterChanges()) { ins += c.toB - c.fromB; del += c.toA - c.fromA }
            " +$ins −$del"
        } else " select ${tr.state.selection.ranges.size}x"
        add("tx $event$what$join")
    }

    fun key(key: String, path: KeyPath) = add("key $key → ${path.label}")
}

@Composable
private fun Chip(label: String, selected: Boolean, ink: Color, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) Color(0xFF4BBAA7).copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = TextStyle(color = ink, fontSize = 12.sp),
    )
}

@Composable
private fun Message(text: String, ink: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BasicText(text, style = TextStyle(color = ink, fontSize = 13.sp)) }
}

/**
 * The in-app benchmark on the open 10k-line document: 200 keystrokes in the middle (after 50 of
 * warm-up), each dispatched at a random point of a frame (as a key press arrives) and timed from the
 * dispatch to the end of the paint that shows it (`keystroke`: this includes waiting for the
 * display's next frame, up to one vsync period) and as the work of that
 * paint's frame alone (`keystrokeWork`: frame start -> paint end); then 400 frames of wheel
 * scrolling from the top, each frame's work timed; then real key events typed at a human pace
 * through the real input path, each timed from the event to the paint showing it. Returns JSON.
 */
suspend fun runBench(session: SampleSession, stats: FrameStats, scrollDriver: suspend () -> Unit, typeDriver: suspend () -> Unit): String {
    val view = session.view
    view.dispatch(TransactionSpec(selection = EditorSelection.cursor(view.state.doc.lineStart(5000) + 8), scrollIntoView = true))
    repeat(30) { withFrameNanos { } }
    val keys = ArrayList<Double>()
    val keyWork = ArrayList<Double>()
    val random = kotlin.random.Random(42)
    repeat(250) { i ->
        // A key press lands anywhere within a frame, not right after one: a random delay first.
        delay(random.nextLong(0, 17))
        val before = stats.draws
        val t0 = stats.now()
        val at = view.state.selection.main.head
        val change = if (i % 8 == 7) ChangeSpec(at - 1, at) else ChangeSpec(at, at, "x")
        view.dispatch(TransactionSpec(changes = listOf(change), scrollIntoView = true, userEvent = "input"))
        while (stats.draws == before) withFrameNanos { }
        if (i >= 50) { keys += stats.lastDrawEnd - t0; keyWork += stats.lastWork }
        repeat(4) { withFrameNanos { } } // the worker's recolouring lands between keystrokes
    }
    view.dispatch(TransactionSpec(selection = EditorSelection.cursor(0), scrollIntoView = true))
    repeat(30) { withFrameNanos { } }
    stats.resetWork()
    val intervals = ArrayList<Double>()
    var last = -1L
    kotlinx.coroutines.coroutineScope {
        val ticker = launch { while (true) withFrameNanos { t -> if (last > 0) intervals += (t - last) / 1e6; last = t } }
        scrollDriver()
        ticker.cancel()
    }
    val work = stats.workTimes()
    val reached = view.state.doc.lineIndexAt(view.viewport.value.first.coerceAtLeast(0))
    // Real key events through the real input path (the hidden field, its diff, the transaction).
    stats.inputLatencies.clear()
    stats.inputByKind.clear()
    stats.inputToChange.clear()
    stats.changesInsideKeyEvent = 0
    typeDriver()
    repeat(10) { withFrameNanos { } }
    val input = stats.inputLatencies.toList()
    val toChange = stats.inputToChange.toList()
    fun f(d: Double) = FrameStats.fmt(d)
    return """{"keystroke":{"p50":${f(FrameStats.pct(keys, 50))},"p95":${f(FrameStats.pct(keys, 95))},"max":${f(keys.max())}},""" +
        """"keystrokeWork":{"p50":${f(FrameStats.pct(keyWork, 50))},"p95":${f(FrameStats.pct(keyWork, 95))},"max":${f(keyWork.max())}},""" +
        """"scrollWork":{"p50":${f(FrameStats.pct(work, 50))},"p95":${f(FrameStats.pct(work, 95))},"max":${f(work.maxOrNull() ?: 0.0)},"frames":${work.size}},""" +
        """"scrollInterval":{"p50":${f(FrameStats.pct(intervals, 50))},"p95":${f(FrameStats.pct(intervals, 95))}},"reachedLine":$reached,""" +
        """"keyEventToPaint":{"n":${input.size},"p50":${f(FrameStats.pct(input, 50))},"p95":${f(FrameStats.pct(input, 95))},"max":${f(input.maxOrNull() ?: 0.0)}},""" +
        """"keyEventToChange":{"p50":${f(FrameStats.pct(toChange, 50))},"p95":${f(FrameStats.pct(toChange, 95))},"insideKeyEvent":${stats.changesInsideKeyEvent}},""" +
        """"keyEventToPaintByKey":{""" + stats.inputByKind.entries.joinToString(",") { (k, v) ->
            """"$k":{"n":${v.size},"p50":${f(FrameStats.pct(v, 50))},"p95":${f(FrameStats.pct(v, 95))},"max":${f(v.max())}}"""
        } + "}}"
}
