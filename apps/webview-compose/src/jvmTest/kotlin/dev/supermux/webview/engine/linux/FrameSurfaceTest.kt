package dev.supermux.webview.engine.linux

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSurfaceHints
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalTestApi::class)
class FrameSurfaceTest {
    private fun engineOn(view: FakeWpeView) = LinuxWebEngine(
        EngineConfig(WebProfile.Ephemeral), view, WebKitScripts("webview", "webview-out-t", "webview-in-t"),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), WpeClipboard({ null }) {},
    )

    @Test fun theViewIsSizedToTheSurfaceInLogicalPixels() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineOn(view)
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp)) }
        waitForIdle()
        assertContains(runOnUiThread { view.calls.toList() }, "setSize 200 100 ${density.density.toDouble()}")
        runOnUiThread { engine.release() }
    }

    @Test fun aPressFocusesThePageAndReachesWebKitInLogicalPixels() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineOn(view)
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp).testTag("page")) }
        val at = with(density) { Offset(50.dp.toPx(), 20.dp.toPx()) }
        onNodeWithTag("page").performMouseInput { click(at) }
        waitForIdle()
        val calls = runOnUiThread { view.calls.toList() }
        assertContains(calls, "pointer ${WpeNative.POINTER_DOWN} 50 20 ${KeyMapping.BUTTON_PRIMARY}")
        assertContains(calls, "pointer ${WpeNative.POINTER_UP} 50 20 ${KeyMapping.BUTTON_PRIMARY}")
        assertContains(calls, "setFocus true")
        runOnUiThread { engine.release() }
    }

    @Test fun keysGoToTheFocusedPage() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineOn(view)
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp).testTag("page")) }
        onNodeWithTag("page").performMouseInput { click() }
        onNodeWithTag("page").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val calls = runOnUiThread { view.calls.toList() }
        assertContains(calls, "key true ${0xff0d} 36")
        assertContains(calls, "key false ${0xff0d} 36")
        runOnUiThread { engine.release() }
    }

    @Test fun aPasteTellsWebKitTheClipboardBeforeThePageGetsTheKey() = runComposeUiTest {
        // Ctrl+V: the glue answers the page's paste on the WebKit thread, which never waits on the JVM.
        val view = FakeWpeView()
        val system = java.awt.datatransfer.Clipboard("test").apply { setContents(java.awt.datatransfer.StringSelection("from another app"), null) }
        val engine = LinuxWebEngine(
            EngineConfig(WebProfile.Ephemeral), view, WebKitScripts("webview", "webview-out-t", "webview-in-t"),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), WpeClipboard({ system }) { view.calls += "clipboard $it" },
        )
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp).testTag("page")) }
        onNodeWithTag("page").performMouseInput { click() }
        waitForIdle()
        runOnUiThread { system.setContents(java.awt.datatransfer.StringSelection("copied since"), null) }
        onNodeWithTag("page").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        waitForIdle()
        val calls = runOnUiThread { view.calls.toList() }
        assertEquals("clipboard from another app", calls.first { it.startsWith("clipboard") }, "at the focus")
        val told = calls.indexOf("clipboard copied since")
        val pasted = calls.indexOf("key true ${0x76} 55")
        assertTrue(told in 0 until pasted, "$calls")
        runOnUiThread { engine.release() }
    }

    @Test fun theWheelScrollsThePageButInAListTheList() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineOn(view)
        var inList by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = inList)) {
                engine.Surface(Modifier.size(200.dp, 100.dp).testTag("page"))
            }
        }
        onNodeWithTag("page").performMouseInput { scroll(1f) }
        waitForIdle()
        assertEquals(1, runOnUiThread { view.calls.count { it.startsWith("scroll") } })
        inList = true
        waitForIdle()
        onNodeWithTag("page").performMouseInput { scroll(1f) }
        waitForIdle()
        assertEquals(1, runOnUiThread { view.calls.count { it.startsWith("scroll") } }, "the list's, not the page's")
        runOnUiThread { engine.release() }
    }

    @Test fun aFrameIsDrawnOneToOneFromTheTopLeftNeverStretched() = runComposeUiTest {
        // While WebKit catches up with a new size, its frame is drawn as it is: stretched with nearest-neighbour
        // filtering, a page jumps and a 1 px difference at a fractional scale shows a seam.
        val view = FakeWpeView()
        val engine = engineOn(view)
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp).testTag("page")) }
        waitForIdle()
        runOnUiThread {
            val page = WebContent.Html("<p>hi</p>", WebOrigin("one-to-one"))
            engine.load(page)
            engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, page.origin.url))
            engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, page.origin.url, frameSeq = 0L))
            view.fill = 0xFF3366CC.toInt()
            view.frame = Triple(40, 30, 3L)
            engine.onEvent(WpeEvent.Frame(3))
        }
        waitForIdle()
        val pixels = onNodeWithTag("page").captureToImage().toPixelMap()
        assertEquals(Color(0xFF3366CC), pixels[10, 10])
        assertNotEquals(Color(0xFF3366CC), pixels[60, 10], "beyond the frame's width")
        assertNotEquals(Color(0xFF3366CC), pixels[10, 50], "below the frame")
        runOnUiThread { engine.release() }
    }

    @Test fun aDeliveredFrameIsPulledAndDrawn() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineOn(view)
        setContent { engine.Surface(Modifier.size(200.dp, 100.dp)) }
        waitForIdle()
        val width = with(density) { 200.dp.roundToPx() }
        val height = with(density) { 100.dp.roundToPx() }
        runOnUiThread {
            // A document committed: an engine shows no frame before one.
            val page = WebContent.Html("<p>hi</p>", WebOrigin("surface"))
            engine.load(page)
            engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, page.origin.url))
            engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, page.origin.url, frameSeq = 0L))
            view.frame = Triple(width, height, 3L)
            engine.onEvent(WpeEvent.Frame(3))
        }
        waitForIdle()
        onRoot().captureToImage()
        assertEquals(3L, runOnUiThread { engine.frames.seq })
        assertEquals(3L, runOnUiThread { engine.frames.drawnSeq })
        runOnUiThread { engine.release() }
    }
}
