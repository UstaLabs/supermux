package dev.supermux.webview.engine.android

import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.runtime.snapshotSize
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshots, dark pages and console forwarding (Task 16), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineSnapshotTest {
    /** Sharp snapshots, Task 3: at the screen's own density (3 pixels a dp at most), in full colour. */
    @Test fun aSnapshotIsThePageAsShownAtTheScreensDensityAndNoneBeforeItPaints() = EngineHarness.run {
        val page = TestPages.marked("#00ff00")
        main {
            configure(setupFor(page))
            load(page)
        }
        assertNull(main { snapshot() }, "nothing before the first paint")
        await("the first paint") { it == EngineEvent.FirstPaint }
        val image = assertNotNull(main { snapshot() })
        val (width, height) = main { webView.width to webView.height }
        // The screen's density, 3 pixels a dp at most, drawn at once.
        val size = snapshotSize(width, height, width / activity.resources.displayMetrics.density)
        assertEquals(size.width, image.width)
        assertEquals(size.height, image.height)
        assertEquals(ImageBitmapConfig.Argb8888, image.config)
        val pixels = image.toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2].toArgb()
        val red = center shr 16 and 0xff
        val green = center shr 8 and 0xff
        val blue = center and 0xff
        assertTrue(red < 40 && green > 200 && blue < 40, "the page's green, not white: #${Integer.toHexString(center)}")
    }

    @Test fun noSnapshotWithoutASurface() {
        val harness = EngineHarness.open(surface = {})
        try {
            kotlinx.coroutines.runBlocking {
                harness.loadMarked(TestPages.solid("#00ff00"))
                delay(1_000)
                assertNull(harness.main { snapshot() })
            }
        } finally {
            harness.close()
        }
    }

    @Test fun darkReachesThePageAndFollowsTheLeaseMidway() = EngineHarness.run {
        loadAndPaint(TestPages.scheme)
        seenOrAwait("light") { it == EngineEvent.TitleChanged("dark false") }
        main { configure(setupFor(TestPages.scheme, dark = true)) }
        await("dark, mid-lease") { it == EngineEvent.TitleChanged("dark true") }
    }

    @Test fun nativeConsoleIsForwardedOnlyForPagesWithoutTheShim() = EngineHarness.run {
        loadAndPaint(TestPages.console, setupFor(TestPages.console, bridgeOrigins = emptySet()))
        val line = seenOrAwait("the native console line") { it is EngineEvent.Console } as EngineEvent.Console
        assertEquals("hello from the page", line.message.message)
        loadAndPaint(TestPages.console)
        seenOrAwait("the shim's console line") { (it as? EngineEvent.PageMessage)?.json?.contains("hello from the page") == true }
        expectNone("a native console line too", millis = 1_000) { it is EngineEvent.Console }
    }
}
