package dev.supermux.webview.engine.ios

import androidx.compose.ui.graphics.Color
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.EngineConfig
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIView
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The view the engine's surface composes. A page moving to a new one (a parked page adopted) keeps its size until its
 * new place has one: laid out at nothing, the page would reflow and lose its scroll position.
 */
class EngineContainerViewTest {
    private val engine = IosWebEngine.create(EngineConfig(WebProfile.Ephemeral))

    private val UIView.size: Pair<Double, Double> get() = frame.useContents { size.width to size.height }

    @AfterTest fun release() {
        engine.release()
        collectGarbage()
    }

    @Test fun aPageKeepsItsSizeUntilItsNewPlaceHasOne() {
        val page = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val container = EngineContainerView(engine)
        container.adopt(page)
        assertSame(container, page.superview)
        assertEquals(320.0 to 480.0, page.size, "no size yet: the page keeps its own")
        container.setFrame(CGRectMake(0.0, 0.0, 300.0, 400.0))
        container.layoutSubviews()
        assertEquals(300.0 to 400.0, page.size)
        container.setFrame(CGRectMake(0.0, 0.0, 0.0, 0.0))
        container.layoutSubviews()
        assertEquals(300.0 to 400.0, page.size, "collapsed to nothing, it only hides the page")
    }

    @Test fun aLateReleaseNeverTakesThePageFromItsNewPlace() {
        val page = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
        val old = EngineContainerView(engine)
        val new = EngineContainerView(engine)
        old.adopt(page)
        new.adopt(page)
        old.letGo(page)
        assertSame(new, page.superview)
    }

    /**
     * A transparent page's view lies under the Compose canvas: without an underlay, its hole shows what is under the
     * canvas through the page. With one, the web view and its container are painted in it, the web view non-opaque so
     * the page's own colours win.
     */
    @Test fun aTransparentPageLiesOnItsUnderlay() {
        val chat = Color(0xFF336699)
        val container = EngineContainerView(engine)
        engine.attach(container)
        assertTrue(container.backgroundColor?.isEqual(UIColor.clearColor) == true, "nothing before a lease")
        engine.configure(setup.copy(transparentBackground = true, underlay = chat))
        val want = listOf(0x33 / 255.0, 0x66 / 255.0, 0x99 / 255.0, 1.0)
        assertTrue(engine.webView.backgroundColor.isNear(want), "${engine.webView.backgroundColor}")
        assertTrue(container.backgroundColor.isNear(want), "${container.backgroundColor}")
        assertFalse(engine.webView.isOpaque())
        engine.configure(setup.copy(transparentBackground = true))
        assertTrue(engine.webView.backgroundColor?.isEqual(UIColor.clearColor) == true, "without one, as before")
        assertTrue(container.backgroundColor?.isEqual(UIColor.clearColor) == true)
    }

    /**
     * Whether this colour's sRGB components are [rgba], to a part in 255: never `isEqual`, which wants the same colour
     * space and the same bits, and Compose's colour is single precision.
     */
    private fun UIColor?.isNear(rgba: List<Double>): Boolean = this != null && memScoped {
        val c = List(4) { alloc<DoubleVar>() }
        getRed(c[0].ptr, green = c[1].ptr, blue = c[2].ptr, alpha = c[3].ptr) &&
            c.zip(rgba).all { (got, want) -> abs(got.value - want) < 0.5 / 255 }
    }

    private val setup = PageSetup(
        javaScriptEnabled = true,
        inspectable = false,
        dark = false,
        transparentBackground = false,
        userAgentSuffix = null,
        bridgeOrigins = emptySet(),
    )
}
