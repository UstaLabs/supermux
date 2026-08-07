package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JTextArea

/**
 * Probe 4: does a Compose modal painted OVER a heavyweight Swing interop child
 * actually RECEIVE MOUSE INPUT?
 *
 * `compose.interop.blending=true` fixed compositing on Metal (probe 2) but Ahmet
 * reports the buttons are dead. This probe separates the two questions by making
 * both outcomes observable without interpretation:
 *
 *  - the Compose modal's button is a solid MAGENTA (#FF00FF) rectangle, so a
 *    screenshot can be searched for it programmatically instead of by eye;
 *  - every click is counted and printed, with a marker prefix, so a synthetic
 *    click's landing place is read off a log rather than assumed;
 *  - a Toolkit AWTEventListener logs the ACTUAL AWT source of every mouse press,
 *    which is the ground truth for "who is topmost for hit-testing".
 *
 * Knobs (env):
 *   SM_BLENDING=true|false    -> compose.interop.blending
 *   SM_LAYERS=WINDOW|COMPONENT-> compose.layers.type
 *   SM_MODAL=dialog|menu|popup|dialogwindow|none
 *   SM_NEUTER=none|disable|listeners|invisible|zerosize
 *       what to do to the Swing child while the modal is open.
 *   SM_DUMP=1                 -> dump the AWT tree (class, bounds, lightweight?)
 *
 * Never ships: test source only.
 */

private const val TAG = "PROBE4"

private var composeClicks = 0
private var swingClicks = 0

fun main() {
    System.getenv("SM_BLENDING")?.let { System.setProperty("compose.interop.blending", it) }
    System.getenv("SM_LAYERS")?.let { System.setProperty("compose.layers.type", it) }
    System.getenv("SM_RENDER_API")?.let { System.setProperty("skiko.renderApi", it) }

    val modal = System.getenv("SM_MODAL") ?: "dialog"
    val neuter = System.getenv("SM_NEUTER") ?: "none"

    println(
        "$TAG CONFIG blending=${System.getProperty("compose.interop.blending")} " +
            "layers=${System.getProperty("compose.layers.type")} " +
            "modal=$modal neuter=$neuter"
    )

    // Ground truth for hit-testing: who does AWT itself hand the press to?
    Toolkit.getDefaultToolkit().addAWTEventListener(
        AWTEventListener { e ->
            if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED) {
                println(
                    "$TAG AWT-PRESS at=(${e.xOnScreen},${e.yOnScreen}) " +
                        "source=${e.source?.javaClass?.name} " +
                        "component=${(e.source as? Component)?.let { c -> "${c.javaClass.simpleName}@${c.bounds}" }}"
                )
                System.out.flush()
            }
        },
        AWTEvent.MOUSE_EVENT_MASK,
    )

    application {
        val state = rememberWindowState(
            position = WindowPosition(120.dp, 120.dp),
            size = DpSize(1100.dp, 820.dp),
        )
        Window(onCloseRequest = ::exitApplication, state = state, title = "interop-input-probe") {
            var compose by remember { mutableStateOf(0) }
            var swing by remember { mutableStateOf(0) }
            var modalOpen by remember { mutableStateOf(modal != "none") }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                if (System.getenv("SM_DUMP") != null) dumpTree(window)
                reportFlags()
                println("$TAG READY window=${window.locationOnScreen} size=${window.size}")
                System.out.flush()
            }

            fun onCompose(label: String) {
                composeClicks += 1
                compose = composeClicks
                println("$TAG HIT compose label=$label total=$composeClicks")
                System.out.flush()
            }

            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    Column(Modifier.fillMaxSize()) {
                        // Header strip: never covered by anything, always readable.
                        Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF202830))) {
                            Text(
                                "COMPOSE=$compose  SWING=$swing",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        // Stand-in for JediTerm: a heavyweight Swing interop child.
                        SwingPanel(
                            background = Color(0xFF7B1FA2),
                            modifier = Modifier.fillMaxWidth().height(560.dp),
                            factory = {
                                JTextArea("HEAVYWEIGHT SWING PANEL\n".repeat(40)).apply {
                                    font = Font(Font.MONOSPACED, Font.BOLD, 18)
                                    background = java.awt.Color(0x7B, 0x1F, 0xA2)
                                    foreground = java.awt.Color.WHITE
                                    addMouseListener(object : java.awt.event.MouseAdapter() {
                                        override fun mousePressed(e: MouseEvent) {
                                            swingClicks += 1
                                            swing = swingClicks
                                            println("$TAG HIT swing total=$swingClicks at=(${e.xOnScreen},${e.yOnScreen})")
                                            System.out.flush()
                                        }
                                    })
                                }
                            },
                            update = { ta -> applyNeuter(ta, neuter, modalOpen) },
                        )
                        Text(
                            "below the swing panel",
                            color = Color.White,
                            modifier = Modifier.padding(8.dp),
                        )
                    }

                    when (modal) {
                        "dialog" -> if (modalOpen) {
                            AlertDialog(
                                onDismissRequest = { modalOpen = false },
                                title = { Text("DIALOG") },
                                text = { Text("Click the magenta target below.") },
                                confirmButton = { MagentaTarget("dialog") { onCompose("dialog") } },
                            )
                        }
                        "menu" -> Box(Modifier.padding(start = 60.dp, top = 160.dp)) {
                            DropdownMenu(
                                expanded = modalOpen,
                                onDismissRequest = { modalOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { MagentaTarget("menu") { } },
                                    onClick = { onCompose("menu") },
                                )
                                repeat(2) { i ->
                                    DropdownMenuItem(
                                        text = { Text("filler $i") },
                                        onClick = { onCompose("menu-filler-$i") },
                                    )
                                }
                            }
                        }
                        "popup" -> if (modalOpen) {
                            Popup(offset = IntOffset(300, 300)) {
                                MagentaTarget("popup") { onCompose("popup") }
                            }
                        }
                        "dialogwindow" -> if (modalOpen) {
                            DialogWindow(
                                onCloseRequest = { modalOpen = false },
                                title = "dialog-window",
                            ) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(0xFF00E676)),
                                ) {
                                    MagentaTarget("dialogwindow") { onCompose("dialogwindow") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A click target that is trivially findable in a screenshot: pure #FF00FF, big,
 * and nothing else in the probe uses that colour.
 */
@Composable
private fun MagentaTarget(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF00FF)),
        shape = androidx.compose.foundation.shape.RectangleShape,
        modifier = Modifier.size(220.dp, 70.dp),
    ) {
        Text("TARGET", color = Color.Black)
    }
}

/** Candidate 1: make the AWT child stop competing for the pointer. */
private fun applyNeuter(ta: JTextArea, mode: String, modalOpen: Boolean) {
    when (mode) {
        "disable" -> {
            ta.isEnabled = !modalOpen
            // Also the interop container chain, up to (not including) the Compose canvas.
            var p: Container? = ta.parent
            var hops = 0
            while (p != null && hops < 3) {
                p.isEnabled = !modalOpen
                p = p.parent
                hops += 1
            }
        }
        "listeners" -> {
            ta.isFocusable = !modalOpen
            if (modalOpen) {
                ta.mouseListeners.forEach { ta.removeMouseListener(it) }
                ta.mouseMotionListeners.forEach { ta.removeMouseMotionListener(it) }
                ta.mouseWheelListeners.forEach { ta.removeMouseWheelListener(it) }
            }
        }
        "invisible" -> {
            var p: Container? = ta.parent
            var hops = 0
            while (p != null && hops < 2) {
                p.isVisible = !modalOpen
                p = p.parent
                hops += 1
            }
        }
        "zerosize" -> {
            val p = ta.parent
            if (p != null && modalOpen) p.setBounds(0, 0, 0, 0)
        }
        else -> Unit
    }
}

private fun dumpTree(root: Component) {
    fun walk(c: Component, depth: Int) {
        val pad = "  ".repeat(depth)
        val idx = (c.parent as? Container)?.let { par ->
            par.components.indexOfFirst { it === c }
        } ?: -1
        println(
            "$TAG TREE $pad[$idx] ${c.javaClass.name} bounds=${c.bounds} " +
                "lw=${c.isLightweight} vis=${c.isVisible} en=${c.isEnabled}"
        )
        if (c is Container) c.components.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    System.out.flush()
}

private fun reportFlags() {
    val skia = mutableListOf<String>()
    fun walk(c: Component) {
        val n = c.javaClass.name
        if (n.contains("SkiaLayer") || n.contains("SkiaSwingLayer")) {
            val api = runCatching { c.javaClass.getMethod("getRenderApi").invoke(c).toString() }
                .getOrElse { "?" }
            skia += "$n renderApi=$api lw=${c.isLightweight}"
        }
        if (c is Container) c.components.forEach { walk(it) }
    }
    java.awt.Window.getWindows().forEach { walk(it) }
    println("$TAG SKIA $skia")
    val flags = runCatching {
        val cls = Class.forName("androidx.compose.ui.ComposeFeatureFlags")
        val inst = cls.getField("INSTANCE").get(null)
        fun flag(getter: String): Any? {
            val f = cls.getMethod(getter).invoke(inst)
            return f.javaClass.getMethod("getValue").invoke(f)
        }
        "useInteropBlending=${flag("getUseInteropBlending")} layerType=${flag("getLayerType")}"
    }.getOrElse { "<${it.javaClass.simpleName}: ${it.message}>" }
    println("$TAG FLAGS $flags")
    System.out.flush()
}
