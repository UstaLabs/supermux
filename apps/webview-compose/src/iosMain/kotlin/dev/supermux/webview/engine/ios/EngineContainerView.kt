package dev.supermux.webview.engine.ios

import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * The view an engine's [IosWebEngine.Surface] composes: one per composition, holding the engine's `WKWebView`.
 * - Native-view detach: [adopt] takes the web view from wherever it was (UIKit would too, implicitly), and [letGo]
 *   removes it only if it is still here. UIKit interop runs `onRelease` late (CMP-10437), after the next view may have
 *   taken the web view already: that late release must not take it from the new one.
 * - The web view takes this view's size, but never nothing: a container not laid out yet (a parked page adopted) or
 *   collapsed leaves it the size it had, since a page laid out at nothing reflows and loses its scroll position.
 * - It tells the engine nothing of touches: Compose hit-tests it for every touch on it, a drag it keeps to scroll the list
 *   included. The relay reports the user's input in the page itself.
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
        fit(view)
        addSubview(view)
    }

    fun letGo(view: UIView) {
        if (view.superview === this) view.removeFromSuperview()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        for (child in subviews) fit(child as UIView)
    }

    /** [view] takes this view's size, unless this view has none. */
    private fun fit(view: UIView) {
        if (bounds.useContents { size.width > 0.0 && size.height > 0.0 }) view.setFrame(bounds)
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        engine.onSurfaceWindowChanged(this)
    }
}
