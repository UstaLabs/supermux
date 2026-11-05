package dev.supermux.webview.sample

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.sample.checks.CheckScreen
import dev.supermux.webview.sample.checks.EngineChecks
import dev.supermux.webview.sample.checks.MacCheckPlatform
import dev.supermux.webview.sample.checks.MacChecks
import dev.supermux.webview.sample.checks.Measurements
import dev.supermux.webview.sample.checks.MotionChecks
import dev.supermux.webview.sample.chat.ChatProbe
import dev.supermux.webview.sample.chat.ChatRun
import dev.supermux.webview.sample.chat.ChatState
import dev.supermux.webview.sample.chat.PreviewMarkers
import dev.supermux.webview.sample.checks.ReviewChecks
import dev.supermux.webview.sample.checks.TrackpadChecks
import dev.supermux.webview.sample.linux.linuxChecksMain
import dev.supermux.webview.sample.linux.linuxDrawLabMain
import dev.supermux.webview.sample.linux.linuxProfileProbeMain
import dev.supermux.webview.sample.linux.linuxLabMain
import dev.supermux.webview.sample.linux.wpePerfLab
import dev.supermux.webview.sample.windows.windowsChecksMain
import dev.supermux.webview.sample.windows.windowsLabMain
import dev.supermux.webview.sample.windows.windowsProbeMain
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.delay
import java.io.File
import kotlin.system.exitProcess

/**
 * The desktop sample: `./gradlew :webview-sample:run`, or on the Mac through `device-checks/mac-*.sh`. With the macOS
 * engine's glue (a Mac) its pages run on WKWebView; elsewhere on the library's fake engine (solid colours, each content
 * its own). Each perf-lab run prints a `perf-lab` line. On Linux, `-Dwebview.sample.engine=wpe` runs the pages on WPE
 * WebKit instead, with its runtime installed (the library README's Linux section); for device-checks/linux-run.sh,
 * `-Dwebview.sample.checks=true` runs the Linux engine's checks and exits, and with `-Dwebview.sample.out=<dir>` too
 * the WPE sample runs unattended and leaves its screenshots there. On Windows, `-Dwebview.sample.engine=webview2` and
 * `-Dwebview.sample.checks=windows` do the same on WebView2 (winvm.sh lab|checks, on the VM). On a Mac:
 * - `-engine-checks` runs the engine's conformance checks instead, prints a `CHECK` line for each, and exits; `-only
 *   <name>` runs that one, also one too long for every run.
 * - `-engine-measure` runs the measurements, which print `MEASURE` lines too.
 * - `-out <dir>`: where the checks save their frames.
 * - `-perf-lab` runs the perf lab's Reading and Scroll tests, with web views and then still images, and exits.
 * - `-screen chat|perflab|overlays|checks` opens that screen (the chat demo unless told otherwise); with `-shots <dir>`
 *   the app asks for one screenshot of it and exits.
 * - `-chat-run read|scroll|fling` starts that run of the chat 3 s after launch; it prints a `chat-perf:` line.
 * - `-chat-skew` scrolls the chat by trackpad gestures and measures how far its live previews' pages are from their
 *   cards (ChatSkewRun): a `MEASURE chat-skew` line, then it exits. `-no-frame-sync` moves page views at their layout,
 *   not with skiko's frames: its baseline.
 * - `-chat-probe` turns the chat's probe on (ChatProbe): its runs print what moved under the reader's eyes, the
 *   previews' resizes and the slow frames, as `chat-probe:` lines.
 * - `-chat-shift` reads the chat by trackpad gestures with the probe on (ChatShiftRun), to its start and back, then
 *   exits. `-exit-after-run` makes a `-chat-run` exit once its run has printed its line.
 * - `-window <width>x<height>` opens the window at that size, in dp (1100 x 1000 by default).
 */
fun main(args: Array<String>) {
    when (System.getProperty("webview.sample.checks")) {
        "true", "linux" -> return linuxChecksMain(System.getProperty("webview.sample.only"))
        "windows" -> return windowsChecksMain(System.getProperty("webview.sample.only"))
    }
    System.getProperty("webview.sample.draw")?.let { return linuxDrawLabMain(it) }
    System.getProperty("webview.sample.probe")?.let { probe ->
        // On Windows, the ephemeral check's probe reads an ephemeral store; any other name is a profile, as on Linux.
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        return if (windows && probe == "ephemeral") windowsProbeMain() else linuxProfileProbeMain(probe)
    }
    if (System.getProperty("webview.sample.engine") == "wpe") return wpeMain(System.getProperty("webview.sample.out")?.let(::File))
    if (System.getProperty("webview.sample.engine") == "webview2") return windowsLabMain(System.getProperty("webview.sample.out")?.let(::File))

    val checks = "-engine-checks" in args
    val measure = "-engine-measure" in args
    val perfLab = "-perf-lab" in args
    val chatSkew = "-chat-skew" in args
    val chatShift = "-chat-shift" in args
    val chatProbe = chatShift || "-chat-probe" in args
    // The previews draw their lines from their first load on (PreviewMarkers).
    if (chatSkew) PreviewMarkers.enabled = true
    // Page views that move at their layout, not with skiko's frames: the skew runs' baseline.
    if ("-no-frame-sync" in args) MacEngineTesting.frameSync = false
    val only = argument(args, "-only")
    val out = File(argument(args, "-out") ?: System.getProperty("java.io.tmpdir"))
    val real = MacEngineTesting.isAvailable
    val shots = argument(args, "-shots")?.let(::File)
    val screen = argument(args, "-screen")?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        ?: Screen.Chat
    val chatRun = argument(args, "-chat-run")?.let { name -> ChatRun.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    // -window <width>x<height>, in dp: a phone's width puts the chat's previews at a phone's card width.
    val windowSize = argument(args, "-window")?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
    val label = if (real) MACOS_ENGINE_LABEL else FAKE_ENGINE_LABEL
    application {
        val state = rememberWindowState(width = (windowSize?.get(0) ?: 1100).dp, height = (windowSize?.get(1) ?: 1000).dp)
        Window(onCloseRequest = ::exitApplication, title = "webview-sample · $label", state = state) {
            if (checks || measure) {
                val platform = remember { MacCheckPlatform(window, out, state) }
                LaunchedEffect(Unit) {
                    // skiko's layer joins the window about the first frame (M8); then the backing can be painted.
                    while (window.windowHandle == 0L) withFrameNanos { }
                    MacEngineTesting.paintBacking(window, MacCheckPlatform.BACKING)
                }
                val list = when {
                    measure -> Measurements.all
                    only == null -> EngineChecks.all + MacChecks.all + ReviewChecks.all + MotionChecks.all + TrackpadChecks.all
                    else -> EngineChecks.all + EngineChecks.onDemand + MacChecks.all + ReviewChecks.all + MotionChecks.all + TrackpadChecks.all
                }
                CheckScreen(list, only, platform)
            } else if (perfLab) {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                SampleApp(lab, engineLabel = label, initialScreen = Screen.PerfLab)
                LabRuns(lab)
            } else if (chatSkew) {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                val chat = remember(lab) { ChatState(lab) }
                SampleApp(lab, engineLabel = label, initialScreen = Screen.Chat, chatState = chat)
                ChatSkewRun(window, chat, out)
            } else if (chatShift) {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                // -chat-baseline: still pictures instead of web views, what the web views are measured against.
                val chat = remember(lab) { ChatState(lab, probe = ChatProbe()).apply { baseline = "-chat-baseline" in args } }
                SampleApp(lab, engineLabel = label, initialScreen = Screen.Chat, chatState = chat)
                ChatShiftRun(window, chat, out)
            } else {
                val lab = remember { if (real) platformPerfLab() else samplePerfLab() }
                val chat = remember(lab) { ChatState(lab, probe = if (chatProbe) ChatProbe() else null) }
                SampleApp(
                    lab,
                    engineLabel = label,
                    initialScreen = screen,
                    pickFiles = { request -> pickFiles(window, request) },
                    chatRun = chatRun,
                    chatState = chat,
                )
                if (shots != null) ShotRequest(window, shots, screen.name.lowercase())
                // -exit-after-run: the chat's run, then `CHECKS DONE`, which `CHAT_RUN=… mac-engine-checks.sh` waits for.
                if (chatRun != null && "-exit-after-run" in args) {
                    LaunchedEffect(chat) {
                        while (chat.lastReport == null) delay(200)
                        println("CHECKS DONE passed=1 failed=0")
                        exitProcess(0)
                    }
                }
            }
        }
    }
}

private fun argument(args: Array<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

/** On Linux with `-Dwebview.sample.engine=wpe`: the sample on WPE WebKit, by hand, or unattended into [out]. */
private fun wpeMain(out: File?) {
    if (out != null) return linuxLabMain(out)
    application {
        val lab = remember { wpePerfLab() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · $LINUX_ENGINE_LABEL",
            state = rememberWindowState(width = 1100.dp, height = 1000.dp),
        ) {
            SampleApp(lab, engineLabel = LINUX_ENGINE_LABEL)
        }
    }
}

/** AWT's file dialog (on macOS, the system's open panel), as the checks page's picker: paths, or null if cancelled. */
private fun pickFiles(window: java.awt.Frame, request: dev.supermux.webview.FileChooserRequest): List<String>? {
    val dialog = java.awt.FileDialog(window, "Choose files", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = request.allowMultiple
    dialog.isVisible = true
    return dialog.files.map { it.absolutePath }.takeIf { it.isNotEmpty() }
}
