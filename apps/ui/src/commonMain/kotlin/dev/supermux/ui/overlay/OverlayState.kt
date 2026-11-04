// The small rules behind the overlay: which state its living avatar draws, how loud the voice it reacts
// to is, and how big a shared screenshot gets. Pure, shared by every platform's overlay.
package dev.supermux.ui.overlay

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The avatar state the overlay draws. Holding it to talk always shows it listening; a reply typing out
 * is spoken; from letting go until the answer comes ([thinking]: your words are on their way, before
 * the Agent reports busy) it works; a stopped session sleeps; otherwise the same rule as the sidebar's
 * Agents (busy, then waiting with an unread reply, then dozing after a quiet while unless you are
 * touching or reading it).
 */
fun overlayAvatarState(
    listening: Boolean, speaking: Boolean, thinking: Boolean, online: Boolean, working: Boolean,
    unread: Boolean, dozing: Boolean, awake: Boolean,
): String = when {
    listening -> "listening"
    speaking -> "speaking"
    thinking -> "working"
    !online -> "asleep"
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
 * One frame of the level an avatar shows, eased toward the voice's [target] over [dtMs]: up fast (about
 * 30 ms), down slowly (about 150 ms), as the avatar format promises, so a mouth follows speech without
 * flickering.
 */
fun smoothLevel(shown: Float, target: Float, dtMs: Float): Float {
    val tau = if (target > shown) 30f else 150f
    return shown + (target - shown) * (1f - exp(-dtMs.coerceAtLeast(0f) / tau))
}

/**
 * A stand-in for the Agent's voice while a reply types out (it has no audio yet): the [chunk] just
 * typed opens its mouth by how many vowels it holds; a space or punctuation is a breath.
 */
fun speechLevel(chunk: CharSequence): Float {
    if (chunk.none { it.isLetter() }) return 0.1f
    val vowels = chunk.count { it.lowercaseChar() in VOWELS }
    return (0.35f + 0.6f * vowels / chunk.length).coerceIn(0f, 1f)
}

private const val VOWELS = "aeiouyıöüâêîôûàáèéìíòóùúäëïœæ"

/**
 * The Agent the overlay shows: the one picked on this device while it still exists, else the default
 * Agent, else the first. Null when there are no Agents.
 */
fun <T> pickOverlayAgent(agents: List<T>, chosenId: String?, id: (T) -> String, isDefault: (T) -> Boolean): T? =
    chosenId?.let { c -> agents.firstOrNull { id(it) == c } } ?: agents.firstOrNull(isDefault) ?: agents.firstOrNull()

/**
 * What to remember when the user picks [pickedId]: nothing for the default Agent, so the overlay keeps
 * following the default if it changes later; the Agent's id otherwise.
 */
fun overlayChoiceToStore(pickedId: String, defaultId: String?): String? = pickedId.takeIf { it != defaultId }

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
