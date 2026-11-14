// What the overlay's bubble says when the mic didn't open (#62).
package dev.supermux.ui.overlay

import dev.supermux.ui.platform.MicTrouble
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

/** Said plainly, as the OS tells why: "allow" only for a mic the OS really keeps from supermux. */
internal fun micTroubleNote(trouble: MicTrouble, agent: String): String = when (trouble) {
    MicTrouble.NONE_FOUND -> localized(Str.overlay_mic_none, agent)
    MicTrouble.NOT_ALLOWED -> localized(Str.overlay_mic_not_allowed)
    MicTrouble.DIDNT_OPEN -> localized(Str.overlay_mic_didnt_open)
}
