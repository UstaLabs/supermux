// Hold Space to dictate (push-to-talk) in the composer field: a short press types a space as it
// always has, holding the key past [SPACE_HOLD_MS] starts dictation and letting go stops it.
package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.coroutines.Job

/** How long Space must stay down before it means "dictate" rather than "type a space". */
const val SPACE_HOLD_MS = 350L

/**
 * How long a release that came before the mic opened waits for it to open, so it can be stopped.
 * Past this the recording is left to the Stop button: a permission prompt took the user elsewhere.
 */
const val SPACE_HOLD_START_WAIT_MS = 5_000L

/** What a key event is, as far as [SpaceHoldGesture] cares. */
enum class SpaceHoldKey {
    /** Space went down (the first press, or the platform's auto-repeat of it). */
    SpaceDown,

    /** Space came up. */
    SpaceUp,

    /**
     * The character event that types the space, where the platform sends one apart from the
     * key-down (desktop's AWT `KEY_TYPED`; the field inserts text on it, not on the press).
     */
    SpaceTyped,

    /** Any other key went down. */
    OtherDown,

    /** Anything else (other keys' key-ups and typed characters). */
    Ignored,
}

/** Classifies a hardware [KeyEvent] for [SpaceHoldGesture]. */
fun KeyEvent.spaceHoldKey(): SpaceHoldKey = when {
    key == Key.Spacebar && type == KeyEventType.KeyDown -> SpaceHoldKey.SpaceDown
    key == Key.Spacebar && type == KeyEventType.KeyUp -> SpaceHoldKey.SpaceUp
    // Desktop maps AWT's KEY_TYPED to `Unknown`, with the typed character as the code point.
    type == KeyEventType.Unknown && utf16CodePoint == ' '.code -> SpaceHoldKey.SpaceTyped
    type == KeyEventType.KeyDown -> SpaceHoldKey.OtherDown
    else -> SpaceHoldKey.Ignored
}

/**
 * Whether a Space press may turn into a hold at all. Only a bare Space (a chord like Shift+Space
 * or Ctrl+Space belongs to someone else), only from a real keyboard (an IME's space key is typing,
 * and its events do not come with an honest key-up), only where the mic button would be offered and
 * is idle, and never while the slash menu is open (it owns the keys there).
 */
fun spaceHoldEligible(
    fromPhysicalKeyboard: Boolean,
    anyModifier: Boolean,
    micShown: Boolean,
    micBusy: Boolean,
    slashMenuOpen: Boolean,
): Boolean = fromPhysicalKeyboard && !anyModifier && micShown && !micBusy && !slashMenuOpen

/** What the composer has to do in answer to one key event. */
enum class SpaceHoldStep {
    /** Let the event through to the field untouched. */
    Pass,

    /** Consume the event (Space's auto-repeat while it is held). */
    Swallow,

    /** Let the event through AND start the [SPACE_HOLD_MS] timer for [SpaceHoldGesture.press]. */
    Arm,

    /** Let the event through AND cancel the timer: it was a tap (or typing rolled over it). */
    Disarm,

    /** The hold timer fired: take back the space the press typed and start dictation. */
    Start,

    /** Consume the event and stop the dictation the hold started (same as pressing Stop). */
    Stop,
}

/**
 * The tap-versus-hold decision for Space, kept free of Compose and of any clock so it can be driven
 * step by step in a test. The composer feeds it every key event through [onKey], runs the timer
 * it asks for with [SpaceHoldStep.Arm], and calls [onHoldElapsed] when that timer fires.
 *
 * The space is typed by the FIELD, as on any keystroke, and taken back only if the press turns
 * into a hold. Holding it back and inserting it later instead would race the field: when the next
 * key of fast typing goes down before Space comes up, the field applies that key to the text it
 * already holds, so a space written from outside in the same moment would be lost or land after
 * the next letter. Typed by the field, a tap is exactly what it always was.
 */
class SpaceHoldGesture {
    enum class Phase { Idle, Pending, Held }

    var phase: Phase = Phase.Idle; private set

    /** Identifies the current press, so a timer left over from an earlier one cannot fire. */
    var press: Int = 0; private set

    /** The press's own typed character has gone through; any further one is auto-repeat. */
    private var typedSeen = false

    /** Feed one key event. [eligible] is consulted only for a Space that is not already down. */
    fun onKey(kind: SpaceHoldKey, eligible: Boolean): SpaceHoldStep = when (kind) {
        SpaceHoldKey.SpaceDown -> when (phase) {
            Phase.Idle -> if (eligible) {
                phase = Phase.Pending
                press++
                typedSeen = false
                SpaceHoldStep.Arm
            } else {
                SpaceHoldStep.Pass
            }
            // Space is already down: this is the platform's auto-repeat. No spaces while held.
            Phase.Pending, Phase.Held -> SpaceHoldStep.Swallow
        }
        SpaceHoldKey.SpaceTyped -> when (phase) {
            Phase.Idle -> SpaceHoldStep.Pass
            Phase.Pending -> if (!typedSeen) {
                typedSeen = true
                SpaceHoldStep.Pass
            } else {
                SpaceHoldStep.Swallow
            }
            Phase.Held -> SpaceHoldStep.Swallow
        }
        SpaceHoldKey.SpaceUp -> when (phase) {
            Phase.Idle -> SpaceHoldStep.Pass
            Phase.Pending -> { phase = Phase.Idle; SpaceHoldStep.Disarm }
            Phase.Held -> { phase = Phase.Idle; SpaceHoldStep.Stop }
        }
        // Rollover while typing fast: the space was a typed space, and the next key is typing too.
        // While HELD, other keys are the user typing around the dictation; the hold carries on.
        SpaceHoldKey.OtherDown -> if (phase == Phase.Pending) {
            phase = Phase.Idle
            SpaceHoldStep.Disarm
        } else {
            SpaceHoldStep.Pass
        }
        SpaceHoldKey.Ignored -> SpaceHoldStep.Pass
    }

    /** The timer armed for [press] fired. [stillEligible] re-checks that dictation can start now. */
    fun onHoldElapsed(press: Int, stillEligible: Boolean = true): SpaceHoldStep {
        if (phase != Phase.Pending || press != this.press) return SpaceHoldStep.Pass
        if (!stillEligible) {
            phase = Phase.Idle
            return SpaceHoldStep.Pass
        }
        phase = Phase.Held
        return SpaceHoldStep.Start
    }

    /**
     * The field lost focus (its key-up will never arrive here). A pending press was a tap and its
     * space stays; a hold is answered with [SpaceHoldStep.Stop], like letting go.
     */
    fun cancel(): SpaceHoldStep = when (phase) {
        Phase.Idle -> SpaceHoldStep.Pass
        Phase.Pending -> { phase = Phase.Idle; SpaceHoldStep.Disarm }
        Phase.Held -> { phase = Phase.Idle; SpaceHoldStep.Stop }
    }
}

/**
 * Whether [after] is [before] with one space typed into it (over a selection, or at a caret) —
 * the only change the press itself makes. The composer takes the space back only then; if the
 * text changed in any other way meanwhile, it is left alone rather than rewound.
 */
fun isTypedSpaceOf(before: String, after: String): Boolean {
    // after = head + " " + tail, where before = head + (the replaced selection, maybe empty) + tail.
    if (after == before) return false
    val kept = after.length - 1
    if (kept < 0 || kept > before.length) return false
    for (at in 0..kept) {
        if (after[at] != ' ') continue
        val tail = kept - at
        if (before.regionMatches(0, after, 0, at) &&
            before.regionMatches(before.length - tail, after, at + 1, tail)
        ) return true
    }
    return false
}

/** The composer's bookkeeping for one [SpaceHoldGesture]: its timer and what a hold restores. */
internal class SpaceHoldRun {
    var timer: Job? = null

    /** The field's text just before the press typed its space. */
    var before: String? = null

    /** A hold asked the mic to start and it has not opened yet. */
    var startPending = false
}
