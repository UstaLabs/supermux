package dev.supermux.ui.editor

import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EditorCallbacks
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EditorPushPlanner
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A recording [EditorEngine] with no browser behind it.
 *
 * It is NOT a stub: every Kotlin→JS call goes through the REAL shared [EditorPushPlanner], and the
 * JS statements it emits are appended to [js]. So a surface test asserts the exact `cm*` calls a
 * live engine would have made — ordering, queue-until-ready and echo-skip included — without
 * booting Chromium.
 */
internal class FakeEditorEngine(
    lineWrap: Boolean = true,
    fontSize: Int = 13,
    /** False keeps the engine pre-first-paint, so the surface shows its cover and arms the timer. */
    startReady: Boolean = true,
) : EditorEngine {
    private val planner = EditorPushPlanner(lineWrap, fontSize)
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _failed = MutableStateFlow<String?>(null)
    override val failed: StateFlow<String?> = _failed.asStateFlow()
    override var callbacks: EditorCallbacks = EditorCallbacks()

    /** Every JS statement the planner emitted, in order. */
    val js = mutableListOf<String>()
    var disposed = 0
        private set
    var diffRegions = 0
        private set
    var threadUpdates = 0
        private set

    init {
        // Ready from birth: flush the (empty) planner queue but do NOT record it, so [js] holds
        // exactly what the SURFACE pushed.
        if (startReady) {
            planner.onReady()
            _ready.value = true
        }
    }

    /** cm6 "first-painted": flush the planner's queue exactly as a real engine's onReady does. */
    fun markReady() {
        js += planner.onReady()
        _ready.value = true
    }

    fun fail(reason: String) {
        _ready.value = false
        _failed.value = reason
    }

    override fun setDocument(path: String, content: String, scrollTop: Int) {
        js += planner.setDocument(content, path, scrollTop)
    }
    override fun revealLine(line: Int, endLine: Int?) { js += planner.revealLine(line, endLine) }
    override fun setFontSize(px: Int) { js += planner.setFontSize(px) }
    override fun setLineWrap(on: Boolean) { js += planner.setLineWrap(on) }
    override fun setScrollTop(px: Int) { js += planner.setScrollTop(px) }
    override fun readScrollTop(cb: (Int) -> Unit) = cb(0)
    override fun getContent(cb: (String) -> Unit) = cb("")
    override fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) {
        js += "lspConnect($serverId)"
    }
    override fun lspMessage(serverId: String, message: String) { js += "lspMessage($serverId)" }
    override fun lspDisconnect() { js += "lspDisconnect()" }
    override fun showDiffRegion(
        path: String,
        content: String,
        ranges: List<DiffRegionRange>,
        language: String,
        restoreScrollTop: Int?,
        threads: List<DiffRegionThread>,
        composer: DiffRegionComposer?,
    ) { diffRegions++ }
    override fun updateDiffThreads(threads: List<DiffRegionThread>, composer: DiffRegionComposer?) {
        threadUpdates++
    }
    override fun dispose() { disposed++ }
}

/** A factory whose [state] a test drives by hand, handing out [FakeEditorEngine]s. */
internal class FakeEditorEngineFactory(
    initial: EngineState = EngineState.Ready,
    private val startReady: Boolean = true,
) : EditorEngineFactory {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    var inits = 0
        private set
    /** Every engine handed out, newest last. */
    val created = mutableListOf<FakeEditorEngine>()
    val engine: FakeEditorEngine? get() = created.lastOrNull()

    fun moveTo(next: EngineState) { _state.value = next }

    override fun ensureInit() { inits++ }
    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        FakeEditorEngine(lineWrap, fontSize, startReady).also { created += it }
}
