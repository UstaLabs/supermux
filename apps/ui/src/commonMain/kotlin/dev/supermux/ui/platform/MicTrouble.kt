// Why a mic didn't open, as the OS tells it (#62): what the overlay says instead of listening.
package dev.supermux.ui.platform

enum class MicTrouble {
    /** This machine has no microphone at all, right now: nothing to allow, one has to be connected. */
    NONE_FOUND,

    /** There is one, and the OS keeps it from this app: the user can allow it. */
    NOT_ALLOWED,

    /** Neither is known: it is there, allowed, and still didn't open (another app holds it, or a fault). */
    DIDNT_OPEN,
}
