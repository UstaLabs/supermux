// The desktop overlay's global hold-to-talk key (#62): Right ⌥ on a Mac, Right Alt on Windows.
package dev.supermux.desktop.overlay

/**
 * The hold-to-talk key held on its own: after [holdMs] it talks, and letting go sends. Any other key
 * while it is down means it was a shortcut or a character (⌥ symbols on a Mac, AltGr on a Turkish
 * keyboard, where AltGr+Q is @), so nothing starts, or a talk already going is cancelled.
 *
 * Pure: fed key events and times, it answers what to do, so each OS's key hook shares it.
 */
class TalkKeyHold(private val holdMs: Long) {
    enum class Out { Start, Send, Cancel }

    private enum class Mode { Up, Armed, Talking, Spoiled }

    private var mode = Mode.Up
    private var downAt = 0L

    /** The key went down (a repeat while it is down changes nothing). */
    fun keyDown(at: Long): List<Out> {
        if (mode == Mode.Up) {
            mode = Mode.Armed
            downAt = at
        }
        return emptyList()
    }

    /** Any other key, or another modifier, while it is down. */
    fun otherKey(at: Long): List<Out> = when (mode) {
        Mode.Armed -> { mode = Mode.Spoiled; emptyList() }
        Mode.Talking -> { mode = Mode.Spoiled; listOf(Out.Cancel) }
        Mode.Up, Mode.Spoiled -> emptyList()
    }

    /** Time passes: held long enough on its own, it talks. */
    fun tick(at: Long): List<Out> {
        if (mode != Mode.Armed || at - downAt < holdMs) return emptyList()
        mode = Mode.Talking
        return listOf(Out.Start)
    }

    fun keyUp(at: Long): List<Out> {
        val was = mode
        mode = Mode.Up
        return if (was == Mode.Talking) listOf(Out.Send) else emptyList()
    }
}
