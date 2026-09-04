package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.widgets.KeepAlivePanel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel

/**
 * What the JVM view host needs beyond [EditorEngine]: JCEF's browser is an AWT child that must be
 * CREATED inside a realized, full-size panel, and then re-parented into the dark holder. Desktop's
 * engine implements this; nothing in `commonMain` knows it exists.
 */
interface SwingEditorEngine : EditorEngine {
    /** Create the browser NOW (on the EDT, inside a realized panel) and start loading the bundle. */
    fun load()

    /** The AWT child to embed, or null until [load] has run. */
    fun uiComponent(): Component?
}

/**
 * How a heavyweight AWT child steps aside while a modal is open — Compose cannot paint over one, so
 * a dialog or menu over the editor would otherwise be invisible. The default is the identity
 * wrapper (previews, tests, and any host with no modal bookkeeping); `DesktopTheme` provides
 * `HeavyweightModalShield`, which hides by LAYOUT (0×0 + clip), the only kind an AWT child respects.
 *
 * A composition local rather than a direct call because the shield is genuinely desktop-only
 * machinery (`desktop/ui/ModalPresence.kt`) and `:ui` must not depend on the app module.
 */
val LocalHeavyweightShield = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> {
    { content -> content() }
}

/**
 * Hosts the engine's JCEF AWT child. The browser is CREATED here — inside the SwingPanel factory,
 * which the compose-desktop runtime runs on the EDT when the panel is realized at full size — so the
 * windowed CEF browser is born attached to a shown, non-zero window and actually loads its page (the
 * load-forever-if-detached trap this avoids). The [SwingPanel] update block parents the browser's UI
 * component into the dark holder once available; a stable dark holder means no white flash before
 * first paint.
 *
 * `visible = false` keeps the browser alive at 0×0 ([KeepAlivePanel]) rather than disposing it, so a
 * background tab keeps its document and its renderer.
 */
@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val swing = engine as? SwingEditorEngine ?: return
    val holder = remember { JPanel(BorderLayout()).apply { background = java.awt.Color(0x28, 0x2C, 0x34) } }
    KeepAlivePanel(visible = visible) {
        // Step aside while anything modal is open. Unlike the terminal this cannot be rescued by
        // interop blending: JCEF is a NATIVE window, not Swing content, and a dialog over it comes
        // out sheared off at the page's top edge (measured on Metal) — so it hides on every
        // platform. This only ever HIDES an already-realized browser; the factory below still runs
        // at full size, so the born-detached trap it guards against is unaffected.
        LocalHeavyweightShield.current {
            SwingPanel(
                factory = {
                    swing.load() // create the browser NOW, on the EDT, in this realized full-size panel
                    holder
                },
                modifier = modifier,
                update = {
                    val comp = swing.uiComponent()
                    if (comp != null && comp.parent !== holder) {
                        holder.removeAll()
                        holder.add(comp, BorderLayout.CENTER)
                        holder.revalidate()
                        holder.repaint()
                    }
                },
            )
        }
    }
}
