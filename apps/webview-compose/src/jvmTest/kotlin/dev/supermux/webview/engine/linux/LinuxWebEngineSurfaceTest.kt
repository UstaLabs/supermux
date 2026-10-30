package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebSettings
import dev.supermux.webview.engine.pageSetup
import dev.supermux.webview.engine.webkit.NavigationKind
import java.awt.Cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LinuxWebEngineSurfaceTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    private fun EngineRig.loadUntilStarted() {
        engine.configure(WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()))
        engine.load(demo)
        engine.onEvent(WpeEvent.Policy(2, demo.origin.url, NavigationKind.Other, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, demo.origin.url))
    }

    private fun FakeWpeView.sizes() = calls.filter { it.startsWith("setSize") }

    @Test fun theViewIsToldItsSizeInLogicalPixelsOnlyWhenItChanges() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(640, 960, 2f)
        rig.engine.onSurfaceSize(640, 960, 2f)
        rig.engine.onSurfaceSize(640, 960, 1f)
        assertEquals(listOf("setSize 320 480 2.0", "setSize 640 960 1.0"), rig.view.sizes())
        rig.close()
    }

    @Test fun aDocumentThatStartedAtAnotherSizeIsToldItsSizeAgainAtItsCommit() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.loadUntilStarted()
        // Laid out after its load began, as a view's first page is.
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.view.calls.clear()
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, demo.origin.url))
        assertEquals(listOf("setSize 321 480 1.0", "setSize 320 480 1.0"), rig.view.sizes())
        rig.close()
    }

    @Test fun aDocumentThatStartedAtItsSizeIsLeftAlone() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.loadUntilStarted()
        rig.view.calls.clear()
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, demo.origin.url))
        assertTrue(rig.view.sizes().isEmpty(), "${rig.view.sizes()}")
        rig.close()
    }

    @Test fun cssCursorsAreAwtCursors() {
        assertEquals(Cursor.HAND_CURSOR, cursorTypeFor("pointer"))
        assertEquals(Cursor.TEXT_CURSOR, cursorTypeFor("text"))
        assertEquals(Cursor.WAIT_CURSOR, cursorTypeFor("progress"))
        assertEquals(Cursor.E_RESIZE_CURSOR, cursorTypeFor("col-resize"))
        assertEquals(Cursor.DEFAULT_CURSOR, cursorTypeFor("zoom-in"))
        assertEquals(Cursor.DEFAULT_CURSOR, cursorTypeFor(null))
    }

    @Test fun theEngineAsksForItsFramesAsBuffersItPulls() = runTest {
        val rig = EngineRig(this)
        assertTrue("frameMode ${WpeDefaults.DAMAGE_SCALE}" in rig.view.calls, "${rig.view.calls}")
        rig.close()
    }

    @Test fun nothingReachesWebKitOnceReleased() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.release()
        rig.view.calls.clear()
        rig.engine.onSurfaceSize(640, 480, 1f)
        rig.engine.onSurfaceFocus(true)
        rig.engine.onSurfaceFrame(full = true)
        assertTrue(rig.view.calls.isEmpty(), "${rig.view.calls}")
        assertTrue(rig.view.pulls.isEmpty())
        rig.close()
    }
}
