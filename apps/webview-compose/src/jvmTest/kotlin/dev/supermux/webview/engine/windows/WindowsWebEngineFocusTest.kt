package dev.supermux.webview.engine.windows

import androidx.compose.ui.focus.FocusRequester
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import java.awt.Canvas
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The engine's side of the keyboard's hand-off (D15): what it tells WebView2 and AWT, on a fake page and window. */
class WindowsWebEngineFocusTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    /** The Compose root's canvas: counts the keyboard given back to it. */
    private class Root : Canvas() {
        var askedForKeys = 0

        override fun requestFocusInWindow(): Boolean {
            askedForKeys++
            return false
        }
    }

    private class Windows(val root: Root) : SurfaceWindows {
        override fun of(window: java.awt.Window?): SurfaceWindows.Placement = SurfaceWindows.Placement(1L, 2L, 1.0)
        override fun canvasComponent(window: java.awt.Window?): java.awt.Component = root
    }

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun aPressMovesTheKeyboardIntoThePage() = runTest {
        // W11: AWT takes native focus on every press, so every press moves it back into WebView2.
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onSurfacePress(FocusRequester())
        rig.engine.onSurfacePress(FocusRequester())
        assertEquals(2, rig.page.calls.count { it == "moveFocus ${WinGlue.FOCUS_PROGRAMMATIC}" }, "${rig.page.calls}")
        rig.close()
    }

    @Test fun aResetGivesTheKeyboardBackToTheApp() = runTest {
        // L25 (reset's unfocus): the next lease's page never inherits the keyboard of this one's.
        val root = Root()
        val rig = WinRig(this, WebProfile.Default)
        rig.engine.windows = Windows(root)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.onSurfaceFocus(true)
        val before = root.askedForKeys
        assertEquals(true, rig.resets()())
        assertEquals(before + 1, root.askedForKeys)
        rig.close()
    }

    @Test fun keysAreOfferedToTheAppOnlyWhileItsSurfaceHasFocus() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        // No surface focused: the glue's question is answered at once, the page keeps its key (D15).
        assertFalse(WinGlue.offerKey(rig.page.token, 'K'.code, 0x25, 3))
        rig.engine.release()
        assertFalse(WinGlue.offerKey(rig.page.token, 'K'.code, 0x25, 3), "nobody answers for a released page")
        rig.close()
    }

    @Test fun requestFocusWithNoSurfaceTellsWebView2Itself() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.requestFocus()
        assertTrue("moveFocus ${WinGlue.FOCUS_PROGRAMMATIC}" in rig.page.calls, "${rig.page.calls}")
        rig.close()
    }
}
