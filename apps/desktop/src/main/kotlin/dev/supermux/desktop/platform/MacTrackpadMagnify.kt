package dev.supermux.desktop.platform

import dev.supermux.ui.chat.TrackpadMagnify
import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * Forward macOS trackpad pinches to [TrackpadMagnify]. AWT never turns a pinch into a mouse or wheel
 * event — the only route is `com.apple.eawt.event.GestureUtilities`, which exists only in a macOS
 * JDK (hence reflection) and lives in a package `java.desktop` does not export (hence the
 * `--add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED` on the desktop run tasks and the
 * packaged app). Without that flag, or off macOS, this logs once and does nothing.
 */
fun installMacTrackpadMagnify(root: JComponent) {
    try {
        val pkg = "com.apple.eawt.event"
        val listener = Class.forName("$pkg.MagnificationListener")
        val gestureListener = Class.forName("$pkg.GestureListener")
        val event = Class.forName("$pkg.MagnificationEvent")
        val getMagnification = event.getMethod("getMagnification")
        val consume = Class.forName("$pkg.GestureEvent").getMethod("consume")
        val proxy = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { self, method, args ->
            when (method.name) {
                "magnify" -> {
                    val e = args!![0]
                    val m = (getMagnification.invoke(e) as Double).toFloat()
                    if (TrackpadMagnify.dispatch(m)) consume.invoke(e)
                    null
                }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "SupermuxMagnificationListener"
                else -> null
            }
        }
        Class.forName("$pkg.GestureUtilities")
            .getMethod("addGestureListenerTo", JComponent::class.java, gestureListener)
            .invoke(null, root, proxy)
    } catch (t: Throwable) {
        println("[Main] trackpad pinch unavailable (${t::class.simpleName}: ${t.message})")
    }
}
