// Card #54: the load-time trace on screen. A floating panel (desktop View ▸ Load Timings) shows
// this launch's timeline, the last chat opens, WebSocket traffic per frame type and every HTTP
// request so far; Copy puts all of it on the clipboard. The console gets the same summaries.
package dev.supermux.ui.perf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.util.PerfReport
import dev.supermux.util.PerfTrace
import kotlinx.coroutines.delay

/** Put this over the app: prints the summaries to the console, and shows the panel when asked. */
@Composable
fun BoxScope.PerfPanelHost() {
    PerfConsoleReporter()
    val visible by PerfTrace.panelVisible.collectAsState()
    if (visible) {
        PerfPanel(
            onClose = { PerfTrace.panelVisible.value = false },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
fun PerfPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(PerfReport.all()) }
    // Twice a second while open: events come in bursts, and this panel must not be what it measures.
    LaunchedEffect(Unit) {
        while (true) {
            text = PerfReport.all()
            delay(500)
        }
    }
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = modifier.width(640.dp).fillMaxHeight(0.85f),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xF0101317),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Load timings · ${PerfTrace.launchId}", color = Color(0xFFE6E8EB), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(PerfReport.all())) }) { Text("Copy", fontSize = 12.sp) }
                    TextButton(onClick = onClose) { Text("Close", fontSize = 12.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text, color = Color(0xFFCDD3DA), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

/**
 * Print the launch summary once it settles (3 s after its last milestone), and each chat open's
 * once it is fully shown, to stdout — the Terminal a hot reload runs in, logcat, the browser console.
 */
@Composable
fun PerfConsoleReporter() {
    LaunchedEffect(Unit) {
        var launchPrinted = false
        val printed = HashSet<String>()
        while (true) {
            delay(1_000)
            val now = PerfTrace.elapsedMs()
            if (!launchPrinted) {
                val last = PerfTrace.events(PerfTrace.launchId).lastOrNull {
                    it.name == "ui.workspaces.drawn" || it.name == "ui.agents.drawn" || it.name == "avatars.loaded"
                }
                if (last != null && now - last.t > 3_000) {
                    println("\n" + PerfReport.launch().joinToString("\n"))
                    launchPrinted = true
                }
            }
            for (trace in PerfTrace.chatTraces(10)) {
                if (trace in printed) continue
                val done = PerfTrace.events(trace).firstOrNull { it.name == "chat.complete" } ?: continue
                if (now - done.t < 1_000) continue
                println("\n" + PerfReport.chat(trace).joinToString("\n"))
                printed += trace
            }
        }
    }
}
