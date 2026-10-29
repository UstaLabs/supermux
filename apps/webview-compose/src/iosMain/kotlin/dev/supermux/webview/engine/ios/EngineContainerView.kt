package dev.supermux.webview.engine.ios

import kotlinx.cinterop.CValue
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UIEventTypeTouches
import platform.UIKit.UIView

/**
 * The view an engine's [IosWebEngine.Surface] composes: one per composition, holding the engine's `WKWebView`.
 * - Native-view detach: [adopt] takes the web view from wherever it was (UIKit would too, implicitly), and [letGo]
 *   removes it only if it is still here. UIKit interop runs `onRelease` late (CMP-10437), after the next view may have
 *   taken the web view already: that late release must not take it from the new one.
 * - It sees every touch on the page first ([hitTest]), and tells the engine: the page is in use, and a navigation it
 *   starts now is the user's.
 * - It tells the engine when it enters or leaves a window: a page counts as painted only on screen.
 */
internal class EngineContainerView(private val engine: IosWebEngine) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        clipsToBounds = true
        backgroundColor = UIColor.clearColor
    }

    fun adopt(view: UIView) {
        if (view.superview === this) return
        view.removeFromSuperview()
        view.setFrame(bounds)
        addSubview(view)
    }

    fun letGo(view: UIView) {
        if (view.superview === this) view.removeFromSuperview()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        for (child in subviews) (child as UIView).setFrame(bounds)
    }

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        val hit = super.hitTest(point, withEvent)
        if (hit != null && withEvent?.type == UIEventTypeTouches) engine.onTouch()
        return hit
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        engine.onSurfaceWindowChanged(this)
    }
}
