package dev.supermux.terminal.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalSessionConfig
import dev.supermux.terminal.compose.TerminalTheme
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the sample app has that is not one terminal: how many terminals there are, which one
 * is visible, and whether the diagnostics are on.
 *
 * The four-terminal layout is not decoration. The spec's hard case is "several surfaces on one
 * process, one of them visible": a mounted-but-inactive surface must release its renderer lease so
 * it stops costing frames, while its session keeps parsing. [terminals] is how the sample makes
 * that visible, and the benchmark drives the same shape.
 */
@Stable
class SampleController(
    private val scope: CoroutineScope,
    count: Int = 1,
    private val config: TerminalSessionConfig = TerminalSessionConfig(),
    private val limits: TerminalLimits = TerminalLimits(),
) {
    /**
     * ONE [SampleDiagnostics] shared by every terminal: the byte totals, the queue high-water marks
     * and the memory are properties of the PROCESS, and four terminals sharing an engine library
     * and a heap is exactly the thing being measured. Per-terminal numbers would hide the sum.
     */
    val diagnostics = SampleDiagnostics()

    var terminals: List<SampleTerminal> by mutableStateOf(emptyList())
        private set

    /** Which terminal is the visible one; the rest stay mounted but inactive. */
    var visibleIndex: Int by mutableStateOf(0)
        private set

    /** The SAMPLE-ONLY panel. A product terminal shows none of it — see [SampleDiagnostics]. */
    var diagnosticsVisible: Boolean by mutableStateOf(true)

    /**
     * Whether the frame-time probe runs.
     *
     * The probe re-arms `withFrameNanos` in a loop, which keeps the host producing frames as fast
     * as it will — that is what makes the percentiles meaningful, and it is also why it is a toggle
     * rather than something always on: a sample that never idles is not a terminal, it is a
     * benchmark.
     */
    var measuring: Boolean by mutableStateOf(true)

    var theme: TerminalTheme by mutableStateOf(TerminalTheme())

    init {
        setCount(count)
    }

    /** Grow or shrink the terminal list; terminals removed are disposed. */
    fun setCount(count: Int) {
        val wanted = count.coerceIn(1, MAX_TERMINALS)
        val current = terminals
        if (wanted == current.size) return
        if (wanted < current.size) {
            current.drop(wanted).forEach { it.dispose() }
            terminals = current.take(wanted)
        } else {
            terminals = current + (current.size until wanted).map { index ->
                SampleTerminal(index + 1, scope, diagnostics, config, limits)
            }
        }
        if (visibleIndex >= terminals.size) visibleIndex = 0
        applyVisibility()
    }

    fun show(index: Int) {
        visibleIndex = index.coerceIn(0, terminals.lastIndex)
        applyVisibility()
    }

    /** Open every terminal that is not open yet. */
    fun openAll() {
        terminals.forEach { it.open(theme = theme) }
        applyVisibility()
    }

    /** The same fixture in every terminal — the benchmark's shape, and the honest comparison. */
    fun selectFixture(fixture: SampleFixture) {
        terminals.forEach { it.selectFixture(fixture) }
    }

    fun setRate(bytesPerSecond: Int) {
        terminals.forEach { it.rateBytesPerSecond = bytesPerSecond }
    }

    fun resetAll() {
        diagnostics.reset()
        terminals.forEach { it.reset() }
    }

    fun disposeAll() {
        terminals.forEach { it.dispose() }
    }

    private fun applyVisibility() {
        terminals.forEachIndexed { index, terminal -> terminal.active = index == visibleIndex }
    }

    companion object {
        /** Four is the number the benchmark specifies; more is a different experiment. */
        const val MAX_TERMINALS = 4
    }
}
