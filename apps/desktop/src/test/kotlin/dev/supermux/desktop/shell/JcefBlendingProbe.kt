package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.supermux.desktop.editor.JcefRuntime
import dev.supermux.desktop.editor.JcefState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel

/**
 * Probe 3: does interop blending survive the app's REAL heavyweight children?
 *
 * Probe 2 proved blending works on Metal for a plain Swing JTextArea. That is
 * the EASY case. The two children that actually matter are:
 *   • JediTermWidget — pure Swing, but a big custom-painted canvas.
 *   • JCEF — a WINDOWED (not offscreen) Chromium, i.e. a real native NSView
 *     child. Nothing about probe 2 says Compose can paint over THAT.
 *
 * So this hosts both for real and puts a dialog + a dropdown across both.
 *
 * Isolation: run with XDG_CONFIG_HOME pointed at a scratch directory. The probe launches with the
 * same pinned JBR/JCEF runtime as the app but gets its own CEF cache, so it cannot disturb a running
 * Supermux Desktop process.
 *
 * Never ships: test source only.
 */
fun main() {
    System.getenv("SM_BLENDING")?.let { System.setProperty("compose.interop.blending", it) }
    println("PROBE3 blending=${System.getProperty("compose.interop.blending")} " +
            "xdg=${System.getenv("XDG_CONFIG_HOME")}")

    application {
        var shuttingDown by remember { mutableStateOf(false) }
        LaunchedEffect(shuttingDown) {
            if (shuttingDown) {
                kotlinx.coroutines.delay(100)
                exitApplication()
            }
        }
        Window(onCloseRequest = { shuttingDown = true }, title = "jcef-blending-probe") {
            if (shuttingDown) return@Window

            val scope = rememberCoroutineScope()
            val jcef by JcefRuntime.state.collectAsState()

            LaunchedEffect(Unit) {
                JcefRuntime.ensureInit(scope)
            }
            LaunchedEffect(jcef) {
                val state = jcef
                println("PROBE3 jcefState=$state")
                if (System.getenv("SM_EXIT_ON_READY") == "1") {
                    when (state) {
                        JcefState.Ready -> {
                            // Leave enough time for SwingPanel to realize the native browser child.
                            kotlinx.coroutines.delay(3000)
                            shuttingDown = true
                        }
                        is JcefState.Error -> error("JCEF probe failed: ${state.msg}")
                        else -> Unit
                    }
                }
            }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                reportRenderApi2(window)
            }
            // Candidate: snapshot the AWT child into a bitmap and draw THAT while the real
            // widget is hidden, so the pane looks unchanged instead of blanking. Only works if
            // Component.paint() actually produces pixels — true for Swing, unknown for a
            // native windowed Chromium. Dump both to PNG and look.
            if (System.getenv("SM_SNAPSHOT") == "1") {
                LaunchedEffect(jcef) {
                    if (jcef == JcefState.Ready) {
                        kotlinx.coroutines.delay(6000)
                        snapshotHeavyweights(window)
                    }
                }
            }

            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    Column(Modifier.fillMaxSize()) {
                        Text("── JEDITERM (real widget) below ──", color = Color.White,
                             modifier = Modifier.padding(4.dp))
                        SwingPanel(
                            background = Color.Black,
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            factory = {
                                JediTermWidget(80, 18, DefaultSettingsProvider())
                            },
                        )
                        Text("── JCEF (real windowed Chromium) below ──", color = Color.White,
                             modifier = Modifier.padding(4.dp))
                        if (jcef == JcefState.Ready) {
                            SwingPanel(
                                background = Color.White,
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                                factory = {
                                    val host = JPanel(BorderLayout())
                                    // SM_OSR=1 → OFFSCREEN (windowless) rendering: CEF paints into a
                                    // bitmap on a plain lightweight JComponent instead of owning a
                                    // native NSView. That is the difference that decides whether
                                    // Compose can blend over it.
                                    val osr = System.getenv("SM_OSR") == "1"
                                    val rendering = if (osr) {
                                        org.cef.browser.CefRendering.OFFSCREEN
                                    } else {
                                        org.cef.browser.CefRendering.DEFAULT
                                    }
                                    println("PROBE3 rendering=$rendering")
                                    val b = JcefRuntime.newClient()?.createBrowser(
                                        "data:text/html," +
                                        "<body style='margin:0;background:%23FF6D00;" +
                                        "font:28px monospace;color:%23000'>" +
                                        "<div>JCEF CHROMIUM PAGE</div>" +
                                        "<input style='font-size:24px' value='type here'>" +
                                        "</body>",
                                        rendering,
                                        false,
                                    )
                                    println("PROBE3 browser=$b uiComponent=${b?.uiComponent?.javaClass?.name}")
                                    b?.uiComponent?.let { host.add(it, BorderLayout.CENTER) }
                                    host
                                },
                            )
                        } else {
                            Text("jcef: $jcef", color = Color.Yellow,
                                 modifier = Modifier.padding(8.dp))
                        }
                    }

                    // Dropdown positioned to straddle the JediTerm surface.
                    var menuOpen by remember { mutableStateOf(true) }
                    Box(Modifier.padding(start = 60.dp, top = 90.dp)) {
                        // onDismissRequest is deliberately a NO-OP: under layers.type=WINDOW a popup
                        // becomes a real OS window, and a focus change can self-dismiss it. Pinning
                        // it open separates "does not PAINT" from "dismissed itself".
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { println("PROBE3 menu dismiss requested") }) {
                            repeat(3) { i ->
                                DropdownMenuItem(text = { Text("MENU OVER TERMINAL $i") }, onClick = {})
                            }
                        }
                    }

                    // The SAME test again, but anchored squarely over the CHROMIUM surface —
                    // the case that actually decides whether the editor pane is fixed.
                    Box(Modifier.padding(start = 520.dp, top = 430.dp)) {
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { println("PROBE3 jcef menu dismiss requested") },
                        ) {
                            repeat(3) { i ->
                                DropdownMenuItem(text = { Text("MENU OVER CHROMIUM $i") }, onClick = {})
                            }
                        }
                    }

                    // A Compose sibling squarely over the JCEF surface.
                    Box(
                        Modifier.padding(start = 500.dp, top = 420.dp)
                            .background(Color(0xFF00E676)).padding(20.dp)
                    ) {
                        Text("COMPOSE OVER CHROMIUM", color = Color.Black)
                    }

                    var dialogOpen by remember { mutableStateOf(System.getenv("SM_NODIALOG") == null) }
                    if (dialogOpen) {
                        AlertDialog(
                            onDismissRequest = { dialogOpen = false },
                            title = { Text("DIALOG OVER BOTH?") },
                            text = { Text("Must cover the terminal AND the Chromium page.") },
                            confirmButton = { TextButton(onClick = { dialogOpen = false }) { Text("OK") } },
                        )
                    }
                }
            }
        }
    }
    // Dispose CEF only after Compose has removed the SwingPanel/browser from the closed window.
    JcefRuntime.dispose()
}

/**
 * Paint each heavyweight child into a BufferedImage the way a "freeze-frame" fix would, and
 * write the result to /tmp. A blank/black PNG means the snapshot trick cannot work for that
 * widget — the pixels live in a native surface Java2D never sees.
 */
private fun snapshotHeavyweights(root: Component) {
    fun walk(c: Component, out: MutableList<Component>) {
        val n = c.javaClass.name
        if (n.contains("JediTermWidget") || n.contains("CefBrowser")) out += c
        if (c is Container) c.components.forEach { walk(it, out) }
    }
    val targets = mutableListOf<Component>()
    walk(root, targets)
    targets.forEachIndexed { i, c ->
        val w = c.width.coerceAtLeast(1)
        val h = c.height.coerceAtLeast(1)
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        runCatching { c.paint(g) }.onFailure { println("PROBE3 SNAP paint threw: $it") }
        g.dispose()
        // How much of the snapshot is actually non-transparent? 0% == the trick is useless here.
        var nonEmpty = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4) {
            if ((img.getRGB(x, y) ushr 24) != 0) nonEmpty++
        }
        val total = ((h + 3) / 4) * ((w + 3) / 4)
        val f = java.io.File("/tmp/snap-$i-${c.javaClass.simpleName.ifBlank { "anon" }}.png")
        javax.imageio.ImageIO.write(img, "png", f)
        println("PROBE3 SNAP ${c.javaClass.name} ${w}x$h nonEmpty=$nonEmpty/$total -> $f")
    }
    System.out.flush()
}

private fun reportRenderApi2(root: Component) {
    val found = mutableListOf<String>()
    fun walk(c: Component) {
        val n = c.javaClass.name
        if (n.contains("SkiaLayer") || n.contains("SkiaSwingLayer")) {
            val api = runCatching {
                c.javaClass.getMethod("getRenderApi").invoke(c).toString()
            }.getOrElse { "n/a" }
            if (api != "n/a") found += "$n renderApi=$api"
        }
        if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    val flags = runCatching {
        val cls = Class.forName("androidx.compose.ui.ComposeFeatureFlags")
        val inst = cls.getField("INSTANCE").get(null)
        val f = cls.getMethod("getUseInteropBlending").invoke(inst)
        f.javaClass.getMethod("getValue").invoke(f).toString()
    }.getOrElse { "n/a" }
    println("PROBE3 RESULT $found useInteropBlending=$flags")
    System.out.flush()
}
