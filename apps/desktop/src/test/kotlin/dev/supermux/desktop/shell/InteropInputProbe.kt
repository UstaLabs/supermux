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
private var zorderTimer: javax.swing.Timer? = null
private var glassPane: javax.swing.JComponent? = null
private var modalOpenLatch = false

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
    // Keyboard is the other half of "can you actually use the modal": a text
    // field in a dialog is useless if the terminal still owns the focus.
    Toolkit.getDefaultToolkit().addAWTEventListener(
        AWTEventListener { e ->
            if (e is java.awt.event.KeyEvent && e.id == java.awt.event.KeyEvent.KEY_PRESSED) {
                println(
                    "$TAG AWT-KEY '${e.keyChar}' source=${e.source?.javaClass?.name} " +
                        "focusOwner=${java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name}"
                )
                System.out.flush()
            }
        },
        AWTEvent.KEY_EVENT_MASK,
    )

    application {
        val state = rememberWindowState(
            position = WindowPosition(40.dp, 40.dp),
            size = DpSize(1100.dp, 820.dp),
        )
        Window(onCloseRequest = ::exitApplication, state = state, title = "interop-input-probe") {
            var compose by remember { mutableStateOf(0) }
            var swing by remember { mutableStateOf(0) }
            var modalOpen by remember { mutableStateOf(modal != "none") }
            var field by remember { mutableStateOf("") }

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
                            factory = { makeTerminalStandIn { c, e ->
                                swingClicks += 1
                                swing = swingClicks
                                println("$TAG HIT swing total=$swingClicks at=(${e.xOnScreen},${e.yOnScreen}) src=${c.javaClass.simpleName}")
                                System.out.flush()
                            } },
                            update = { host -> applyNeuter(host, neuter, modalOpen) },
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
                                text = {
                                    Column {
                                        Text("Click the magenta target below.")
                                        androidx.compose.material3.OutlinedTextField(
                                            value = field,
                                            onValueChange = {
                                                field = it
                                                println("$TAG FIELD '$it'"); System.out.flush()
                                            },
                                            label = { Text("type here") },
                                        )
                                    }
                                },
                                confirmButton = { MagentaTarget("dialog") { onCompose("dialog") } },
                            )
                        }
                        "menu" -> Box(Modifier.padding(start = 60.dp, top = 160.dp)) {
                            DropdownMenu(
                                expanded = modalOpen,
                                onDismissRequest = { modalOpen = false },
                            ) {
                                DropdownMenuItem(
                                    // The inner Button would otherwise swallow the click
                                    // before DropdownMenuItem's onClick ever sees it.
                                    text = { MagentaTarget("menu") { onCompose("menu-button") } },
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
        shape = androidx.compose.ui.graphics.RectangleShape,
        modifier = Modifier.size(220.dp, 70.dp),
    ) {
        Text("TARGET", color = Color.Black)
    }
}

/**
 * Candidate 1: make the AWT child stop competing for the pointer.
 *
 * The tree that matters (measured, SM_DUMP=1) is
 *
 *   ComposeWindowPanel
 *     [0] SwingInteropViewGroup   <- the Swing child; index 0 == TOPMOST for hit-testing
 *     [1] ComposeSceneMediator$InvisibleComponent
 *     [2] WindowSkiaLayerComponent$hierarchyRoot  -> SkiaLayer (heavyweight)
 *
 * Lightweight z-order is front-to-back by index, so the interop group wins every
 * press inside its bounds no matter what the Skia canvas painted on top of it.
 */
private fun applyNeuter(host: OverlayHost, mode: String, modalOpen: Boolean) {
    val ta = host.terminal
    when (mode) {
        "disable" -> {
            // Only the child and its own interop group — never ComposeWindowPanel,
            // which would disable Compose's own input as well.
            ta.isEnabled = !modalOpen
            ta.parent?.isEnabled = !modalOpen
            println("$TAG NEUTER disable ta.enabled=${ta.isEnabled} group.enabled=${ta.parent?.isEnabled}")
            System.out.flush()
        }
        // Candidate 5b: Compose re-asserts the interop z-order on every placement,
        // so hold it down with a repeating timer instead of setting it once.
        "sendtoback-timer" -> {
            val group = ta.parent ?: return
            val host = group.parent ?: return
            if (zorderTimer == null) {
                zorderTimer = javax.swing.Timer(100) {
                    val want = if (modalOpenLatch) host.componentCount - 1 else 0
                    if (host.getComponentZOrder(group) != want) {
                        host.setComponentZOrder(group, want)
                    }
                }.also { it.start() }
            }
            modalOpenLatch = modalOpen
        }
        // Candidate 5: leave the child fully alive, just stop it being topmost.
        "sendtoback" -> {
            val group = ta.parent ?: return
            val host = group.parent ?: return
            val want = if (modalOpen) host.componentCount - 1 else 0
            if (host.getComponentZOrder(group) != want) {
                host.setComponentZOrder(group, want)
                println("$TAG ZORDER group -> $want of ${host.componentCount} (modalOpen=$modalOpen)")
                System.out.flush()
            }
        }
        // Candidate 6: keep the interop group exactly where it is — so blending
        // still composites it and the terminal stays VISIBLE — and instead put a
        // transparent lightweight child ON TOP of the terminal INSIDE the group,
        // which swallows every mouse event and re-dispatches it to the Skia layer
        // (i.e. to Compose). Nothing about the group's bounds or z-order changes.
        "glass" -> {
            val group = ta.parent as? javax.swing.JComponent ?: return
            if (!modalOpen) {
                glassPane?.let { group.remove(it); group.repaint() }
                glassPane = null
                return
            }
            if (glassPane == null) {
                val skia = findSkia(group) ?: run {
                    println("$TAG NEUTER glass NO_SKIA"); return
                }
                val g = object : javax.swing.JComponent() {}
                g.isOpaque = false
                val fwd = object : java.awt.event.MouseAdapter() {
                    private fun send(e: MouseEvent) {
                        skia.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(e.component, e, skia))
                    }
                    override fun mousePressed(e: MouseEvent) {
                        println("$TAG GLASS press -> skia"); System.out.flush(); send(e)
                    }
                    override fun mouseReleased(e: MouseEvent) = send(e)
                    override fun mouseClicked(e: MouseEvent) = send(e)
                    override fun mouseMoved(e: MouseEvent) = send(e)
                    override fun mouseDragged(e: MouseEvent) = send(e)
                    override fun mouseEntered(e: MouseEvent) = send(e)
                    override fun mouseExited(e: MouseEvent) = send(e)
                }
                g.addMouseListener(fwd)
                g.addMouseMotionListener(fwd)
                group.add(g, 0)
                glassPane = g
                println("$TAG NEUTER glass installed skia=${skia.javaClass.name}")
                System.out.flush()
            }
            // `update` only runs on recomposition, and the first one lands before the
            // group has been laid out (width 0) — so keep bounds+z-order pinned.
            if (zorderTimer == null) {
                zorderTimer = javax.swing.Timer(100) {
                    val g = glassPane ?: return@Timer
                    if (g.parent !== group) return@Timer
                    if (g.width != group.width || g.height != group.height) {
                        g.setBounds(0, 0, group.width, group.height)
                    }
                    if (group.getComponentZOrder(g) != 0) group.setComponentZOrder(g, 0)
                }.also { it.start() }
            }
            glassPane!!.setBounds(0, 0, group.width, group.height)
            if (group.getComponentZOrder(glassPane) != 0) group.setComponentZOrder(glassPane, 0)
        }
        // Candidate 6b: the SAME idea, but the overlay lives in a container the APP
        // owns (so an ordinary layout manager sizes it — no polling, no reaching
        // into SwingInteropViewGroup), and the event is re-dispatched to the
        // interop group, which already carries ComposeSceneMediator's own mouse
        // listener. No skiko class is named anywhere.
        "glass-own" -> {
            host.setShieldActive(modalOpen)
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


/**
 * What the app would really own: a container holding the terminal, plus a
 * transparent shield it can raise over it while a modal is open.
 *
 * Every child is sized to the host by [doLayout], so nothing polls and nothing
 * touches Compose's own SwingInteropViewGroup — the only Compose-side fact this
 * relies on is that the interop group carries ComposeSceneMediator's mouse
 * listener, which is what makes re-dispatching to `parent` route into the scene.
 */
class OverlayHost(val terminal: JTextArea) : javax.swing.JPanel(null) {
    private val shield = object : javax.swing.JComponent() {
        init { isOpaque = false }
    }

    init {
        add(terminal)
        val fwd = object : java.awt.event.MouseAdapter() {
            private fun send(e: MouseEvent) {
                val target = this@OverlayHost.parent ?: return
                target.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(e.component, e, target))
            }
            override fun mousePressed(e: MouseEvent) {
                println("$TAG SHIELD press -> ${this@OverlayHost.parent?.javaClass?.simpleName}")
                System.out.flush(); send(e)
            }
            override fun mouseReleased(e: MouseEvent) = send(e)
            override fun mouseClicked(e: MouseEvent) = send(e)
            override fun mouseMoved(e: MouseEvent) = send(e)
            override fun mouseDragged(e: MouseEvent) = send(e)
            override fun mouseEntered(e: MouseEvent) = send(e)
            override fun mouseExited(e: MouseEvent) = send(e)
        }
        shield.addMouseListener(fwd)
        shield.addMouseMotionListener(fwd)
    }

    fun setShieldActive(active: Boolean) {
        val has = shield.parent === this
        if (active == has) return
        if (active) add(shield, 0) else remove(shield)
        revalidate(); repaint()
        println("$TAG SHIELD active=$active")
        System.out.flush()
    }

    override fun doLayout() {
        for (c in components) c.setBounds(0, 0, width, height)
    }
}

private fun makeTerminalStandIn(onClick: (Component, MouseEvent) -> Unit): OverlayHost {
    val ta = JTextArea("HEAVYWEIGHT SWING PANEL\n".repeat(40)).apply {
        font = Font(Font.MONOSPACED, Font.BOLD, 18)
        background = java.awt.Color(0x7B, 0x1F, 0xA2)
        foreground = java.awt.Color.WHITE
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = onClick(e.component, e)
        })
    }
    return OverlayHost(ta)
}

/** The heavyweight skiko canvas that carries Compose's own mouse listener. */
private fun findSkia(from: Component): Component? {
    var root: Component = from
    while (root.parent != null) root = root.parent
    var hit: Component? = null
    fun walk(c: Component) {
        if (c.javaClass.name.startsWith("org.jetbrains.skiko.SkiaLayer")) hit = c
        if (c is Container) c.components.forEach { walk(it) }
    }
    walk(root)
    return hit
}

private fun dumpTree(root: Component) {
    fun walk(c: Component, depth: Int) {
        val pad = "  ".repeat(depth)
        val idx = (c.parent as? Container)?.let { par ->
            par.components.indexOfFirst { it === c }
        } ?: -1
        val ml = c.mouseListeners.joinToString(",") { it.javaClass.name.substringAfterLast('.') }
        println(
            "$TAG TREE $pad[$idx] ${c.javaClass.name} bounds=${c.bounds} " +
                "lw=${c.isLightweight} vis=${c.isVisible} en=${c.isEnabled} mouseListeners=[$ml]"
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
