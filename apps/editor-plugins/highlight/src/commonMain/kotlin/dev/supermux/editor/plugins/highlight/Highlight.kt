package dev.supermux.editor.plugins.highlight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.viewportEffectsFacet
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.panelsFacet
import dev.supermux.editor.syntax.LanguageRegistry
import dev.supermux.editor.syntax.QueryKind
import dev.supermux.editor.syntax.Syntax
import dev.supermux.editor.syntax.SyntaxBackend
import dev.supermux.editor.syntax.SyntaxLimits
import dev.supermux.editor.syntax.SyntaxWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Syntax highlighting for a document in [language] (null: plain text): editor-syntax's
 * `Syntax.extension` (the spans field, mapped through every edit, and the language hooks) plus the
 * slot the "syntax off" panel appears in. Data only: the worker lives in a [SyntaxHost].
 */
fun highlight(language: String?): Extension = extensionOf(
    Syntax.extension(language),
    SyntaxHost.offPanel.of(extensionOf()),
    // The worker's viewport rides on the surface's one viewport transaction (no second dispatch).
    viewportEffectsFacet.of { laid -> listOf(Syntax.setViewport.of(laid)) },
)

/**
 * Where a [SyntaxHost] dispatches: [explicit], else [scope]'s dispatcher unless Unconfined (or
 * missing), else [main] (`Dispatchers.Main` when the platform has one); none: an
 * [IllegalStateException] right away.
 */
internal fun resolveUiDispatcher(
    explicit: kotlin.coroutines.CoroutineContext?,
    scope: CoroutineScope,
    main: () -> kotlin.coroutines.CoroutineContext? = { runCatching { kotlinx.coroutines.Dispatchers.Main.also { it.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) } }.getOrNull() },
): kotlin.coroutines.CoroutineContext =
    explicit ?: scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]?.takeIf { it !== kotlinx.coroutines.Dispatchers.Unconfined }
        ?: main()
        ?: throw IllegalStateException("SyntaxHost: the scope's dispatcher is Unconfined (or none) and there is no Main dispatcher: pass uiDispatcher (the UI thread's) or a hop")

/**
 * Owns the [SyntaxWorker] of ONE [EditorView] (spec §5, the host's side of the syntax layer):
 *
 * - posts every transaction's state to the worker (a view listener), and a replaced state too
 *   (`setState`: another file in the same view, which the worker takes as a new document);
 * - hops the worker's results onto the UI thread ([hop], in order), dropping them once closed;
 * - the worker's viewport comes with the surface's one viewport transaction ([highlight]);
 * - [precompile]s the language's queries before the first parse (the web's cold start: a query
 *   compile is one uninterruptible call there);
 * - when syntax turns off for the document (too big, a line too long, a parse too slow), shows the
 *   [OFF_PANEL] panel ([registerWidgets] gives its content) and sets [isOff];
 * - [close] stops the worker, which frees every native handle on its own thread.
 *
 * [scope] is the UI's; the worker runs on its own single thread inside it. The results are
 * dispatched on the UI thread: [hop], else [uiDispatcher], else [scope]'s dispatcher (never an
 * Unconfined one), else `Dispatchers.Main`; with none of them the constructor throws. Compose hosts
 * use [rememberSyntaxHost].
 *
 * ⚠️ **Headless scenes.** `ImageComposeScene` (and any composition without a UI dispatcher) runs its
 * `rememberCoroutineScope()` on Unconfined, and a JVM test may have no `Dispatchers.Main`, or a Swing
 * one that is NOT the thread rendering the scene. Give such a scene its own queued UI dispatcher
 * (`ImageComposeScene(coroutineContext = queue)`, drained between frames) or pass [uiDispatcher] /
 * [hop] explicitly.
 */
class SyntaxHost(
    val view: EditorView,
    private val backend: SyntaxBackend,
    private val registry: LanguageRegistry = LanguageRegistry.default,
    private val scope: CoroutineScope,
    limits: SyntaxLimits = SyntaxLimits(),
    hop: ((() -> Unit) -> Unit)? = null,
    uiDispatcher: kotlin.coroutines.CoroutineContext? = null,
) : AutoCloseable {
    private var closed = false
    private var started = false

    /**
     * Where the worker's results are dispatched: [uiDispatcher], else [scope]'s own dispatcher unless
     * it is Unconfined (a coroutine resumed from the worker would then dispatch ON the worker thread,
     * and an off-thread write to the view's Compose state can be lost), else `Dispatchers.Main`.
     */
    private val hop: (() -> Unit) -> Unit = hop ?: run {
        // Resolved HERE, in the constructor: a host with no UI dispatcher fails when it creates the
        // SyntaxHost (on its own thread, with a clear message), never later on the worker's thread.
        val ui = resolveUiDispatcher(uiDispatcher, scope)
        val hopper: (() -> Unit) -> Unit = { run -> scope.launch(ui) { run() } }
        hopper
    }

    val worker: SyntaxWorker = SyntaxWorker(backend, registry, scope, dispatch = { spec -> this.hop { if (!closed) view.dispatch(spec) } }, limits = limits)

    /** True once syntax is off for the shown document (snapshot state: a host's status line can show it). */
    var isOff: Boolean by mutableStateOf(false)
        private set

    private val removeListener = view.addListener { tr ->
        if (started) post(tr.state)
        // Only the answer to the request the state was still waiting for: an earlier one (a second
        // Mod-i superseded it with one more level) or a stale one (the selection moved) is dropped.
        for (e in tr.effects) e.valueIf(Syntax.parentAnswer)?.let { answer ->
            if (Syntax.pendingParent(tr.startState)?.id == answer.id) selectAnswered(answer)
        }
    }

    /**
     * The worker's answer to a select-parent request (Mod-i, CM6's `selectParentSyntax`): the
     * enclosing nodes, selected in ONE `select` transaction, unless the text or the selection changed
     * since it was asked for (the answer's positions would be another text's, or undo a newer move).
     */
    private fun selectAnswered(answer: dev.supermux.editor.syntax.ParentAnswer) {
        hop {
            if (closed) return@hop
            val st = view.state
            if (Syntax.snapshot(st)?.version != answer.version) return@hop
            val old = st.selection.ranges
            if (old.size * 2 != answer.ranges.size) return@hop
            val now = old.flatMap { listOf(it.from, it.to) }.toIntArray()
            if (answer.requested.isNotEmpty() && !answer.requested.contentEquals(now)) return@hop
            var changed = false
            val ranges = old.mapIndexed { i, r ->
                val a = answer.ranges[2 * i]; val h = answer.ranges[2 * i + 1]
                if (a < 0 || h < 0 || a > st.doc.length || h > st.doc.length) r
                else { changed = true; dev.supermux.editor.core.SelectionRange(a, h) }
            }
            if (changed) view.dispatch(TransactionSpec(selection = dev.supermux.editor.core.EditorSelection.create(ranges, st.selection.mainIndex), scrollIntoView = true, userEvent = "select"))
        }
    }
    private val removeReplace = view.addReplaceListener { st -> if (started) post(st) }

    /** The language of the shown document (null: plain text). */
    val language: String? get() = Syntax.snapshot(view.state)?.language

    /**
     * Start: the worker parses the current state and follows the view from now on. The viewport
     * reaches the worker through the surface's own viewport transaction ([highlight] contributes
     * `Syntax.setViewport` to it), so the host runs no collector of its own and nothing outlives
     * [close].
     */
    fun start() {
        if (started || closed) return
        started = true
        post(view.state)
    }

    /** Compile the shown language's queries (and those it always injects), each after [yieldBetween]. */
    suspend fun precompile(onCompile: (String) -> Unit = {}, yieldBetween: suspend () -> Unit = { delay(1) }) {
        val lang = language ?: return
        precompileSyntax(backend, registry, lang, onCompile, yieldBetween)
    }

    /**
     * For a host WITHOUT the Compose surface (which does this itself): the viewport, dispatched as
     * `Syntax.setViewport` when it changed.
     */
    fun onViewport(range: IntRange) {
        if (closed) return
        if (Syntax.snapshot(view.state)?.viewport == range) return
        view.dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(range))))
    }

    private fun post(state: EditorState) {
        worker.onState(state)
        val off = Syntax.isOff(state)
        if (off != isOff) {
            isOff = off
            // Show or hide the panel (a compartment of highlight()): not an edit, no userEvent.
            if (offPanel.get(state) != null) {
                val panel = if (off) panelsFacet.of(Panel(OFF_PANEL, top = false)) else extensionOf()
                hop { if (!closed) view.dispatch(TransactionSpec(effects = listOf(offPanel.reconfigure(panel)))) }
            }
        }
    }

    /** Stop following the view and stop the worker (it frees its native handles; [join] waits). */
    override fun close() {
        if (closed) return
        closed = true
        removeListener()
        removeReplace()
        worker.close()
    }

    suspend fun join() = worker.join()

    companion object {
        /** The panel shown while syntax is off for the document (content: `panel:syntax-off`). */
        const val OFF_PANEL = "syntax-off"

        internal val offPanel = Compartment("highlight.syntaxOff")

        /** Register the [OFF_PANEL] panel's content in [widgets]: a one-line notice in the editor's theme. */
        fun registerWidgets(widgets: WidgetRegistry) {
            widgets.register("panel:$OFF_PANEL") {
                BasicText(
                    "Syntax highlighting is off for this file (too large, a line too long, or too slow to parse).",
                    Modifier.fillMaxWidth().background(theme.gutterBackground).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(color = theme.gutterForeground, fontSize = 12.sp),
                )
            }
        }
    }
}

/**
 * A [SyntaxHost] for [view] while this composition shows it: queries precompiled, then started;
 * closed when [view] changes or leaves the composition. [widgets], when given, gets the "syntax off"
 * panel's content.
 */
@Composable
fun rememberSyntaxHost(
    view: EditorView,
    backend: SyntaxBackend,
    registry: LanguageRegistry = LanguageRegistry.default,
    widgets: WidgetRegistry? = null,
    limits: SyntaxLimits = SyntaxLimits(),
): SyntaxHost {
    val scope = rememberCoroutineScope()
    val host = remember(view, backend) { SyntaxHost(view, backend, registry, scope, limits) }
    DisposableEffect(host) { onDispose { host.close() } }
    LaunchedEffect(host) {
        host.precompile()
        host.start()
    }
    if (widgets != null) DisposableEffect(widgets) { SyntaxHost.registerWidgets(widgets); onDispose { } }
    return host
}

/**
 * Compile [language]'s queries (and those of the languages its documents always inject) before its
 * first document is parsed. On the web a query compile is one uninterruptible call (26-116 ms
 * cold); doing each in its own task, ahead of the first paint, keeps it off the first parse.
 */
suspend fun precompileSyntax(
    backend: SyntaxBackend,
    registry: LanguageRegistry,
    language: String,
    onCompile: (String) -> Unit = {},
    yieldBetween: suspend () -> Unit,
) {
    val languages = listOf(language) + INJECTED[language].orEmpty()
    for (l in languages) {
        yieldBetween()
        backend.ensureLanguage(l)
        for (k in QueryKind.entries) {
            val source = registry.query(l, k) ?: continue
            yieldBetween()
            onCompile("$l/${k.file}")
            backend.sharedQuery(l, source)
        }
    }
}

private val INJECTED = mapOf("markdown" to listOf("markdown_inline"))
