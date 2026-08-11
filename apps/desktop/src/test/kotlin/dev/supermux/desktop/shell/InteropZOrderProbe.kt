package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import dev.supermux.desktop.ui.AlertDialog
import dev.supermux.desktop.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.CompositionLocalProvider
import dev.supermux.desktop.ui.HeavyweightModalShield
import dev.supermux.desktop.ui.LocalModalPresence
import dev.supermux.desktop.ui.ModalPresence
import java.awt.Font
import javax.swing.JTextArea

/**
 * Throwaway probe: can a Compose dialog or dropdown paint ABOVE a SwingPanel?
 *
 * Ahmet: "most modals etc. stays under when there is terminal view". The app's
 * two heavyweight AWT children (JediTerm, JCEF) are documented as unbeatable by
 * Compose siblings — this measures whether a JVM property changes that on
 * Compose 1.11.1, instead of taking either candidate on faith.
 *
 * Set SM_PROBE_LAYERS / SM_PROBE_BLENDING before launching to try each.
 * Never ships: test source only.
 */
fun main() {
    System.getenv("SM_PROBE_LAYERS")?.let { System.setProperty("compose.layers.type", it) }
    System.getenv("SM_PROBE_BLENDING")?.let { System.setProperty("compose.interop.blending", it) }
    println("PROBE layers.type=${System.getProperty("compose.layers.type")} " +
            "blending=${System.getProperty("compose.interop.blending")}")

    application {
        Window(onCloseRequest = ::exitApplication, title = "interop-z-order-probe") {
            val presence = remember { ModalPresence() }
            CompositionLocalProvider(LocalModalPresence provides presence) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    Column(Modifier.fillMaxSize()) {
                        Text("above the swing panel", color = Color.White, modifier = Modifier.padding(8.dp))
                        // Stand-in for JediTerm: a heavyweight Swing child, same interop path.
                        HeavyweightModalShield(Modifier.fillMaxWidth().height(420.dp)) {
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
                        }
                        Text("below the swing panel", color = Color.White, modifier = Modifier.padding(8.dp))
                    }

                    // A dropdown anchored so it opens straight over the Swing panel.
                    var menuOpen by remember { mutableStateOf(true) }
                    Box(Modifier.padding(start = 40.dp, top = 60.dp)) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            repeat(4) { i ->
                                DropdownMenuItem(text = { Text("DROPDOWN ITEM $i") }, onClick = {})
                            }
                        }
                    }

                    // Candidate C: a real top-level OS window, not an in-canvas layer.
                    if (System.getenv("SM_PROBE_DIALOGWINDOW") != null) {
                        DialogWindow(onCloseRequest = {}, title = "dialog-window") {
                            Box(Modifier.fillMaxSize().background(Color(0xFF00E676))) {
                                Text("DIALOGWINDOW ON TOP", color = Color.Black,
                                     modifier = Modifier.padding(24.dp))
                            }
                        }
                    }
                    // Candidate D: Popup, which Compose may host in its own window.
                    if (System.getenv("SM_PROBE_POPUP") != null) {
                        // Squarely in the MIDDLE of the swing panel, not at its edge.
                        Popup(offset = androidx.compose.ui.unit.IntOffset(300, 250)) {
                            Box(Modifier.background(Color(0xFFFFEB3B)).padding(30.dp)) {
                                Text("POPUP ON TOP", color = Color.Black)
                            }
                        }
                    }

                    var dialogOpen by remember { mutableStateOf(System.getenv("SM_PROBE_NODIALOG") == null) }
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
}
