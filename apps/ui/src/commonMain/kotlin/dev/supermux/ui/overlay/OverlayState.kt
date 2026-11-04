// The small rules behind the overlay: which state its living avatar draws, how loud the voice it reacts
// to is, and how big a shared screenshot gets. Pure, shared by every platform's overlay.
package dev.supermux.ui.overlay

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The avatar state the overlay draws. Holding it to talk always shows it listening; a stopped session
 * sleeps; a reply typing out is spoken; otherwise the same rule as the sidebar's Agents (busy, then
 * waiting with an unread reply, then dozing after a quiet while unless you are touching or reading it).
 */
fun overlayAvatarState(listening: Boolean, speaking: Boolean, online: Boolean, working: Boolean, unread: Boolean, dozing: Boolean, awake: Boolean): String = when {
    listening -> "listening"
    !online -> "asleep"
    speaking -> "speaking"
    working -> "working"
    unread -> "waiting"
    dozing && !awake -> "asleep"
    else -> "idle"
}

/**
 * The live voice level 0..1 an avatar's level-driven parts follow (see the avatar FORMAT.md): a mic's
 * RMS (0..1 of full scale) as normalised loudness, −50 dBFS → 0 and −10 dBFS → 1.
 */
fun voiceLevel(rms: Float): Float {
    if (rms <= 0f) return 0f
    val dbfs = 20f * log10(rms)
    return ((dbfs + 50f) / 40f).coerceIn(0f, 1f)
}

/**
 * A shared screenshot's size: scaled down so its long side is at most [maxEdge] (models read about that
 * much anyway, and the upload stays small), never scaled up, its shape kept.
 */
fun shareImageSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= maxEdge) return width to height
    val k = maxEdge.toFloat() / long
    return maxOf(1, (width * k).roundToInt()) to maxOf(1, (height * k).roundToInt())
}
