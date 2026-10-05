// Windows window chrome: content edge to edge like macOS and Linux, KEEPING Windows' own
// minimise / maximise / close (so snap layouts on the maximise button, Aero snap and the system
// menu all stay native).
//
// JBR's custom title bar (`WindowDecorations.CustomTitleBar`) does on Windows what it does on
// macOS: the client area extends over the caption, JBR draws the native caption buttons at the
// top-right ([CustomTitleBar.getRightInset] wide) and asks the Java side, per mouse event, whether
// a point in the band is caption (drag, double-click maximise, snap) or client. We answer from the
// same drag regions macOS and Linux register (empty sidebar band, tab-strip tails): see
// [MacChromeRegions]. `controls.dark` follows the app theme so the native buttons render light or
// dark to match.
//
// Engages only on a JetBrains Runtime with WindowDecorations (the packaged app ships one);
// otherwise, or with SUPERMUX_SYSTEM_TITLEBAR=1, the window keeps the normal frame and menu bar.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Height of the Windows band: the pane tab strip's height (as on Linux), so the band IS the strip row. */
val WindowsTitleBarHeight = LinuxTitleBarHeight

/** Room for Windows' three caption buttons when JBR hasn't reported its inset yet (3 × 46 px at 100%). */
val WindowsCaptionButtonsFallbackWidth = 138.dp

object WindowsWindowChrome {
    /** Pure gate: Windows, not opted out (SUPERMUX_SYSTEM_TITLEBAR=1), and JBR decorations present. */
    fun shouldEngage(windows: Boolean, optOut: String?, decorationsAvailable: Boolean): Boolean =
        windows && optOut?.trim()?.lowercase() !in setOf("1", "true", "yes") && decorationsAvailable

    /** The right inset to reserve: JBR's live one, or the fallback before it has one. */
    fun captionButtonsWidth(nativeRightInset: Float?): Dp =
        if (nativeRightInset == null || nativeRightInset <= 0f) WindowsCaptionButtonsFallbackWidth else nativeRightInset.dp
}

/** The engaged Windows chrome for one window. */
class WindowsWindowChromeInstall(
    val regions: MacChromeRegions,
    val titleBar: WindowDecorations.CustomTitleBar,
    private val decorations: WindowDecorations,
    private val window: ComposeWindow,
    val captionButtonsWidth: Dp,
) {
    /** Native caption buttons light or dark, matching the app theme. */
    fun setDark(dark: Boolean) {
        if (titleBar.properties["controls.dark"] == dark) return
        titleBar.putProperty("controls.dark", dark)
        // Re-apply so the change reaches the native buttons.
        runCatching { decorations.setCustomTitleBar(window, titleBar) }
    }
}

/**
 * Installs the custom title bar on [window] when [WindowsWindowChrome.shouldEngage], else null
 * (normal frame). Call on Windows only; [dark] seeds the caption buttons' look.
 */
@Composable
fun rememberWindowsWindowChrome(window: ComposeWindow, dark: Boolean): WindowsWindowChromeInstall? {
    val setup = remember(window) {
        val optOut = System.getenv(LinuxWindowChrome.OPT_OUT_ENV)
        val decorations = if (WindowsWindowChrome.shouldEngage(true, optOut, decorationsAvailable = true)) {
            runCatching { JBR.getWindowDecorations() }.getOrNull()
        } else {
            null
        }
        val bar = decorations?.let { d ->
            runCatching {
                d.createCustomTitleBar().also { tb ->
                    tb.height = WindowsTitleBarHeight.value
                    tb.putProperty("controls.visible", true)
                    tb.putProperty("controls.dark", dark)
                    d.setCustomTitleBar(window, tb)
                }
            }.onFailure { println("[WindowsWindowChrome] custom title bar failed: $it") }.getOrNull()
        }
        println(
            if (bar != null) "[WindowsWindowChrome] custom title bar on (native caption buttons kept)"
            else "[WindowsWindowChrome] system title bar (optOut=$optOut decorations=${decorations != null})",
        )
        if (decorations != null && bar != null) decorations to bar else null
    } ?: return null
    val (decorations, bar) = setup
    val regions = remember(window) { MacChromeRegions() }
    DisposableEffect(window, bar) {
        val listener = object : MouseAdapter() {
            // JBR contract (as on macOS): update the hit test on every mouse event but EXITED/WHEEL.
            // Content-relative AWT points → Compose px with this monitor's transform.
            private fun update(e: MouseEvent) {
                val t = window.graphicsConfiguration?.defaultTransform
                val p = Offset((e.x * (t?.scaleX ?: 1.0)).toFloat(), (e.y * (t?.scaleY ?: 1.0)).toFloat())
                bar.forceHitTest(!regions.allowsNativeDrag(p))
            }

            override fun mousePressed(e: MouseEvent) = update(e)
            override fun mouseReleased(e: MouseEvent) = update(e)
            override fun mouseEntered(e: MouseEvent) = update(e)
            override fun mouseDragged(e: MouseEvent) = update(e)
            override fun mouseMoved(e: MouseEvent) = update(e)
        }
        window.addMouseListener(listener)
        window.addMouseMotionListener(listener)
        onDispose {
            window.removeMouseListener(listener)
            window.removeMouseMotionListener(listener)
        }
    }
    // JBR reports the caption buttons' width once the window is shown, and again after a DPI change.
    var width by remember(bar) { mutableStateOf(WindowsWindowChrome.captionButtonsWidth(bar.rightInset)) }
    LaunchedEffect(bar) {
        while (isActive) {
            val next = WindowsWindowChrome.captionButtonsWidth(runCatching { bar.rightInset }.getOrNull())
            if (next != width) width = next
            delay(500)
        }
    }
    return remember(bar, width) { WindowsWindowChromeInstall(regions, bar, decorations, window, width) }
}
