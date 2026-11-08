package dev.supermux.webview.engine.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject

/** What runs code at the screen's next display tick. The engine's are [DisplayTicks]; tests tick by hand. Main thread. */
internal interface Ticks {
    /** Runs [action] at the next tick. */
    fun next(action: () -> Unit)

    /** Nothing that waits runs any more. */
    fun cancel()
}

/** The screen's display ticks (`CADisplayLink`), for what waits on them: the link runs only while something does. */
internal class DisplayTicks : Ticks {
    private val waiting = ArrayList<() -> Unit>()
    private val target = TickTarget(::tick)
    private var link: CADisplayLink? = null

    override fun next(action: () -> Unit) {
        waiting += action
        if (link != null) return
        val made = CADisplayLink.displayLinkWithTarget(target, NSSelectorFromString("tick"))
        made.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        link = made
    }

    override fun cancel() {
        waiting.clear()
        stop()
    }

    private fun tick() {
        val due = waiting.toList()
        waiting.clear()
        for (action in due) action()
        if (waiting.isEmpty()) stop()
    }

    private fun stop() {
        link?.invalidate()
        link = null
    }
}

/** A display link's target: it retains this until invalidated. */
@OptIn(BetaInteropApi::class)
private class TickTarget(private val onTick: () -> Unit) : NSObject() {
    @ObjCAction
    fun tick() = onTick()
}
