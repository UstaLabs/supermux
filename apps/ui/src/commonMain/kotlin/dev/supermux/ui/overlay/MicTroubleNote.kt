// What the overlay's bubble says when the mic didn't open (#62).
package dev.supermux.ui.overlay

import dev.supermux.ui.platform.MicTrouble

/** Said plainly, as the OS tells why: "allow" only for a mic the OS really keeps from supermux. */
internal fun micTroubleNote(trouble: MicTrouble, agent: String): String = when (trouble) {
    MicTrouble.NONE_FOUND -> "No microphone found. Connect one to talk to $agent."
    MicTrouble.NOT_ALLOWED -> "Allow the microphone in supermux to talk to it here."
    MicTrouble.DIDNT_OPEN -> "The microphone didn't open. Try again in a moment."
}
