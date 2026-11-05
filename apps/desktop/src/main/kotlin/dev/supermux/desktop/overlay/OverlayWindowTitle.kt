// The assistant overlay's window titles (#62).
package dev.supermux.desktop.overlay

import java.util.concurrent.atomic.AtomicInteger

private val windowSerial = AtomicInteger()

/**
 * A title no other overlay window has had. macOS finds an overlay window by its title to float it
 * everywhere ([MacOverlayNative.floatEverywhere]), and the bubble just closed can still be listed under the
 * same title: the new one was then never floated (seen on Ahmet's Mac: a reply bubble left at AWT's level 3).
 */
internal fun uniqueOverlayTitle(base: String): String = "$base #${windowSerial.incrementAndGet()}"
