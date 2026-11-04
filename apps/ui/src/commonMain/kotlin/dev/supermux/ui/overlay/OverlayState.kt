// The two small rules behind the overlay's living avatar: which state it draws, and how loud the voice
// it reacts to is. Pure, shared by every platform's overlay.
package dev.supermux.ui.overlay

import kotlin.math.log10

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
