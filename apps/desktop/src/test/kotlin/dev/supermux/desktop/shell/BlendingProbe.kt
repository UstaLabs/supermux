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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JTextArea

/**
 * Probe 2: does `compose.interop.blending=true` let Compose paint OVER a
 * heavyweight SwingPanel, on a GPU backend that actually supports it?
 *
 * Static reading of Compose 1.11.1 says blending is gated on
 * `SkiaLayerComponent.interopBlendingSupported`, which for the default
 * (window) component is true ONLY for DIRECT3D and METAL. So any Linux run
 * (OpenGL or software) is structurally incapable of showing it work — the
 * measurement is only meaningful on macOS/Metal or Windows/D3D.
 *
 * This probe therefore PRINTS THE RENDER API IT ACTUALLY GOT, so a screenshot
 * can never be misread as evidence when skiko silently fell back.
 *
 * Deliberately uses the raw material3 AlertDialog/DropdownMenu and a bare
 * SwingPanel — no ModalPresence/HeavyweightModalShield — so what is measured
 * is Compose's own z-order, not the workaround's.
 *
 * Never ships: test source only.
 */
fun main() {
    System.getenv("SM_BLENDING")?.let { System.setProperty("compose.interop.blending", it) }
    System.getenv("SM_LAYERS")?.let { System.setProperty("compose.layers.type", it) }
    System.getenv("SM_RENDER_API")?.let { System.setProperty("skiko.renderApi", it) }

    println("PROBE2 blending=${System.getProperty("compose.interop.blending")} " +
            "layers=${System.getProperty("compose.layers.type")} " +
            "skiko.renderApi=${System.getProperty("skiko.renderApi")}")

    application {
        Window(onCloseRequest = ::exitApplication, title = "blending-probe") {
            // Report what skiko REALLY chose, plus what Compose concluded about blending.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                reportRenderApi(window)
            }
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    Column(Modifier.fillMaxSize()) {
                        Text("above the swing panel", color = Color.White,
                             modifier = Modifier.padding(8.dp))
                        // Stand-in for JediTerm: a heavyweight Swing child, same interop path.
                        SwingPanel(
                            background = Color(0xFF7B1FA2),
                            modifier = Modifier.fillMaxWidth().height(420.dp),
                            factory = {
                                JTextArea("HEAVYWEIGHT SWING PANEL\n".repeat(30)).apply {
                                    font = Font(Font.MONOSPACED, Font.BOLD, 18)
                                    background = java.awt.Color(0x7B, 0x1F, 0xA2)
                                    foreground = java.awt.Color.WHITE
                                }
                            },
                        )
                        Text("below the swing panel", color = Color.White,
                             modifier = Modifier.padding(8.dp))
                    }

                    // A dropdown anchored so it opens squarely over the Swing panel.
                    var menuOpen by remember { mutableStateOf(true) }
                    Box(Modifier.padding(start = 40.dp, top = 60.dp)) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            repeat(4) { i ->
                                DropdownMenuItem(text = { Text("DROPDOWN ITEM $i") }, onClick = {})
                            }
                        }
                    }

                    // A plain Compose sibling drawn after the panel: the simplest possible
                    // "paint over interop" case, with no popup/layer machinery involved.
                    Box(
                        Modifier.padding(start = 420.dp, top = 200.dp)
                            .background(Color(0xFF00E676)).padding(24.dp)
                    ) {
                        Text("PLAIN COMPOSE SIBLING", color = Color.Black)
                    }

                    var dialogOpen by remember { mutableStateOf(System.getenv("SM_NODIALOG") == null) }
                    if (dialogOpen) {
                        AlertDialog(
                            onDismissRequest = { dialogOpen = false },
                            title = { Text("DIALOG ON TOP?") },
                            text = { Text("If you can read this over the purple panel, it works.") },
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                        )
                    }
                }
            }
        }
    }
}

/** Walk the AWT tree for skiko's SkiaLayer and read the API it actually initialised with. */
private fun reportRenderApi(root: Component) {
    val found = mutableListOf<String>()
    fun walk(c: Component) {
        val n = c.javaClass.name
        if (n.contains("SkiaLayer") || n.contains("SkiaSwingLayer")) {
            val api = runCatching {
                c.javaClass.getMethod("getRenderApi").invoke(c).toString()
            }.getOrElse { "<${it.javaClass.simpleName}: ${it.message}>" }
            found += "$n renderApi=$api"
        }
        if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    println("PROBE2 RESULT skiko.layers=$found")

    // What did Compose itself conclude? Read the same flags it reads.
    val flags = runCatching {
        val cls = Class.forName("androidx.compose.ui.ComposeFeatureFlags")
        val inst = cls.getField("INSTANCE").get(null)
        fun flag(getter: String): Any? {
            val f = cls.getMethod(getter).invoke(inst)
            return f.javaClass.getMethod("getValue").invoke(f)
        }
        "useInteropBlending=${flag("getUseInteropBlending")} layerType=${flag("getLayerType")}"
    }.getOrElse { "<${it.javaClass.simpleName}: ${it.message}>" }
    println("PROBE2 RESULT composeFlags $flags")
    System.out.flush()
}
