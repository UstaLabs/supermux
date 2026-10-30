package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
