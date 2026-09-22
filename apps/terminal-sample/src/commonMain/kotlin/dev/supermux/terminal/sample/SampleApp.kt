package dev.supermux.terminal.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.compose.LocalTerminalEffects
import dev.supermux.terminal.compose.Terminal
import dev.supermux.terminal.compose.rememberTerminalAccessories
import kotlinx.coroutines.delay

/**
 * The standalone terminal sample.
 *
 * It depends on `:terminal-compose` (and through it `:terminal-core`) and on **nothing else of
 * supermux** — no `:shared`, no `:ui`, no broker, no SSH, no pty. Every byte comes from
 * [SampleFixtures]; see [SampleTerminal] for why that matters.
 *
 * Everything below the terminals is SAMPLE-ONLY instrumentation. A product terminal UI must show
 * none of it: the package deliberately exports no counters, and a host that wants numbers builds
 * them the way this file does — on the public seams.
 */
@Composable
fun SampleApp(controller: SampleController) {
    LaunchedEffect(controller) { controller.openAll() }

    // The frame-time probe. Re-arming `withFrameNanos` in a loop is what keeps the host producing
    // frames continuously, so the intervals below are "how long did a frame take", not "how long
    // until something changed". It is the whole basis of the p50/p95 numbers.
    LaunchedEffect(controller.measuring) {
        if (!controller.measuring) return@LaunchedEffect
        while (true) {
            withFrameNanos { controller.diagnostics.onFrameNanos(it) }
        }
    }

    // Memory is polled, never sampled per frame: reading /proc costs more than a frame does.
    var snapshot by remember { mutableStateOf(controller.diagnostics.snapshot()) }
    LaunchedEffect(controller) {
        while (true) {
            controller.diagnostics.sampleMemory()
            snapshot = controller.diagnostics.snapshot()
            delay(POLL_MILLIS)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101311))) {
        SampleControls(controller)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            TerminalStack(controller)
        }
        if (controller.diagnosticsVisible) {
            DiagnosticsPanel(snapshot, controller)
        }
    }
}

@Composable
private fun TerminalStack(controller: SampleController) {
    // Every terminal is COMPOSED and every terminal is LAID OUT at the same size; only the visible
    // one is `active` and only it is drawn last, so it covers the others (each surface paints an
    // opaque background). The inactive ones therefore hold no renderer lease and cost no frames,
    // while their sessions keep parsing — which is the whole point of the `active` flag and the
    // only honest way to run four of these at once.
    //
    // Deliberately NOT a zero-size or `alpha(0)` modifier for the hidden ones: a surface measured
    // to nothing would resize its session to a degenerate grid, and the four terminals have to be
    // the same 120x40-ish shape for the numbers to mean anything.
    val order = controller.terminals.indices.sortedBy { it == controller.visibleIndex }
    Box(Modifier.fillMaxSize()) {
        for (index in order) {
            Box(Modifier.fillMaxSize()) {
                SampleTerminalPane(controller.terminals[index], controller)
            }
        }
    }
}

@Composable
private fun SampleTerminalPane(terminal: SampleTerminal, controller: SampleController) {
    val session = terminal.session
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(terminal.status, color = Color(0xFFD8DED3))
                terminal.failure?.let {
                    Text(
                        it.message.orEmpty(),
                        color = Color(0xFFE06C75),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp),
                    )
                }
                Button(onClick = { terminal.open(theme = controller.theme) }, Modifier.padding(top = 12.dp)) {
                    Text("open session")
                }
            }
        }
        return
    }

    // The passive frame tap: a second collector on a conflated StateFlow. It never acknowledges
    // anything — only the surface does — so it cannot change what the terminal does.
    ObservePublishedFrames(session, controller)

    if (!terminal.mounted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "terminal ${terminal.index} is hidden — the session keeps parsing, the surface is gone",
                color = Color(0xFF8B9488),
            )
        }
        return
    }

    val accessories = rememberTerminalAccessories()
    CompositionLocalProvider(LocalTerminalEffects provides terminal.effects) {
        Terminal(
            session = session,
            modifier = Modifier.fillMaxSize()
                .border(1.dp, if (terminal.active) Color(0xFF2E3A2E) else Color(0xFF1A1E1A)),
            theme = controller.theme,
            active = terminal.active,
            accessories = accessories,
            label = "sample terminal ${terminal.index}",
            onTitle = terminal::onTitle,
            onLink = terminal::onLink,
            onFailure = terminal::onFailure,
        )
    }
}

@Composable
private fun ObservePublishedFrames(session: TerminalSession, controller: SampleController) {
    LaunchedEffect(session) {
        var lastSequence = 0L
        session.viewports.collect { viewport ->
            if (lastSequence != 0L && viewport.sequence > lastSequence + 1) {
                controller.diagnostics.onSequenceGap(viewport.sequence - lastSequence - 1)
            }
            lastSequence = viewport.sequence
            controller.diagnostics.onPublishedFrame(
                columns = viewport.size.columns,
                rows = viewport.size.rows,
                cellWidthPx = viewport.size.cellWidthPx,
                cellHeightPx = viewport.size.cellHeightPx,
                historyRows = viewport.historyRows,
                full = viewport.full,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

@Composable
private fun SampleControls(controller: SampleController) {
    val visible = controller.terminals.getOrNull(controller.visibleIndex)
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (fixture in SampleFixture.entries) {
                FilterChip(
                    selected = visible?.fixture == fixture,
                    onClick = { controller.selectFixture(fixture) },
                    label = { Text(fixture.title) },
                )
            }
        }
        Text(
            visible?.fixture?.summary.orEmpty(),
            color = Color(0xFF8B9488),
            fontSize = 12.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { controller.resetAll() }) { Text("reset") }
            OutlinedButton(onClick = { visible?.hide() }) { Text("hide") }
            OutlinedButton(onClick = { visible?.show() }) { Text("show") }
            OutlinedButton(onClick = { visible?.dispose() }) { Text("dispose") }
            OutlinedButton(onClick = { visible?.open(theme = controller.theme) }) { Text("open") }
            OutlinedButton(onClick = { controller.diagnosticsVisible = !controller.diagnosticsVisible }) {
                Text(if (controller.diagnosticsVisible) "panel off" else "panel on")
            }
            OutlinedButton(onClick = { controller.measuring = !controller.measuring }) {
                Text(if (controller.measuring) "stop probe" else "start probe")
            }
            OutlinedButton(onClick = { controller.setCount(if (controller.terminals.size == 1) 4 else 1) }) {
                Text(if (controller.terminals.size == 1) "4 terminals" else "1 terminal")
            }
            for (index in controller.terminals.indices) {
                FilterChip(
                    selected = index == controller.visibleIndex,
                    onClick = { controller.show(index) },
                    label = { Text("#${index + 1}") },
                )
            }
        }
        val rate = visible?.rateBytesPerSecond ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "rate ${if (rate == 0) "unthrottled" else formatBytes(rate.toLong()) + "/s"}",
                color = Color(0xFFD8DED3),
                fontSize = 12.sp,
                modifier = Modifier.widthIn(min = 180.dp),
            )
            Slider(
                value = rateToSlider(rate),
                onValueChange = { controller.setRate(sliderToRate(it)) },
                valueRange = 0f..1f,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}

/** 0 → 4 KiB/s, 1 → unthrottled; logarithmic in between so the useful range is not one pixel wide. */
private fun sliderToRate(value: Float): Int {
    if (value >= 0.995f) return 0
    val minLog = kotlin.math.ln(4.0 * 1024)
    val maxLog = kotlin.math.ln(64.0 * 1024 * 1024)
    return kotlin.math.exp(minLog + (maxLog - minLog) * value).toInt()
}

private fun rateToSlider(rate: Int): Float {
    if (rate <= 0) return 1f
    val minLog = kotlin.math.ln(4.0 * 1024)
    val maxLog = kotlin.math.ln(64.0 * 1024 * 1024)
    return (((kotlin.math.ln(rate.toDouble()) - minLog) / (maxLog - minLog)).coerceIn(0.0, 1.0)).toFloat()
}

// ---------------------------------------------------------------------------------------------
// SAMPLE-ONLY diagnostics
// ---------------------------------------------------------------------------------------------

@Composable
private fun DiagnosticsPanel(snapshot: DiagnosticsSnapshot, controller: SampleController) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(Color(0xFF080A08))
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "SAMPLE-ONLY DIAGNOSTICS — none of this exists in :terminal-compose",
            color = Color(0xFFC2E58C),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        val queueHeadroom = if (snapshot.outputQueueCapBytes > 0) {
            "${snapshot.pendingOutputHighWaterBytes} / ${snapshot.outputQueueCapBytes} B"
        } else {
            "—"
        }
        val rows = listOf(
            "grid" to "${snapshot.columns}x${snapshot.rows} cells · cell ${snapshot.cellWidthPx}x${snapshot.cellHeightPx} px",
            "output bytes" to "${formatBytes(snapshot.outputBytes)} (${snapshot.outputBytes})",
            "input bytes" to "${formatBytes(snapshot.inputBytes)} (${snapshot.inputBytes}) · responses ${snapshot.responseBytes} B",
            "frame time" to "p50 ${formatDecimal(snapshot.frameP50Millis, 2)} ms · " +
                "p95 ${formatDecimal(snapshot.frameP95Millis, 2)} ms · " +
                "p99 ${formatDecimal(snapshot.frameP99Millis, 2)} ms · " +
                "worst ${formatDecimal(snapshot.worstFrameMillis, 1)} ms · n=${snapshot.framesObserved}",
            "stalls" to ">33 ms ${snapshot.stallsOver33ms} · >100 ms ${snapshot.stallsOver100ms}",
            "frames published" to "${snapshot.framesPublished} (full ${snapshot.framesFull}) · " +
                "observer gaps ${snapshot.sequenceGaps} (${snapshot.framesMissed} frames)",
            "engine history" to "${snapshot.historyRows} rows (peak ${snapshot.historyRowsHighWater}) · " +
                "limit ${snapshot.historyLinesLimit} lines / ${formatBytes(snapshot.historyBytesLimit)}",
            "output queue" to "in-flight ${snapshot.pendingOutputBytes} B · high water $queueHeadroom · " +
                "backpressure ${snapshot.backpressureEvents} waits / ${snapshot.backpressureMicros / 1000} ms",
            "input queue" to "cap ${snapshot.inputQueueCapBytes} B · mailbox ${snapshot.mailboxCapacity} · " +
                "refused enqueues ${snapshot.rejectedEnqueues}",
            "effects" to "titles ${snapshot.titles} · bells ${snapshot.bells} · osc52 ${snapshot.clipboardRequests}",
            "process memory" to "heap ${formatBytes(snapshot.memory.heapUsedBytes)} / " +
                "${formatBytes(snapshot.memory.heapTotalBytes)} · rss ${formatBytes(snapshot.memory.rssBytes)} · " +
                "peak rss ${formatBytes(snapshot.peakRssBytes)} · via ${snapshot.memory.source}",
            "lifecycle" to "sessions opened ${snapshot.sessionsOpened} · disposed ${snapshot.sessionsDisposed} · " +
                "resets ${snapshot.resets} · failures ${snapshot.failures}",
        )
        for ((label, value) in rows) {
            Row {
                Text(
                    label,
                    color = Color(0xFF6F7A6C),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.widthIn(min = 132.dp),
                )
                Text(value, color = Color(0xFFD8DED3), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        snapshot.lastFailure?.let {
            Text("last failure: $it", color = Color(0xFFE06C75), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        val visible = controller.terminals.getOrNull(controller.visibleIndex)
        visible?.title?.let {
            Text("osc 0/2 title: $it", color = Color(0xFF8B9488), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        visible?.lastLink?.let {
            Text(
                "osc 8 link activated (never opened by the surface): $it",
                color = Color(0xFF8B9488),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Text(
            "status: ${visible?.status.orEmpty()} · mounted=${visible?.mounted} active=${visible?.active}",
            color = Color(0xFF6F7A6C),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

private const val POLL_MILLIS = 250L

/**
 * `MaterialTheme` is here only so the sample's own CONTROLS look like controls.
 *
 * It never reaches the terminal: `TerminalTheme` (the package's own, app-theme-free type) is what
 * `Terminal` is given, which is exactly the separation `:terminal-compose` is built around — no
 * Material token can leak into a cell.
 */
@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
