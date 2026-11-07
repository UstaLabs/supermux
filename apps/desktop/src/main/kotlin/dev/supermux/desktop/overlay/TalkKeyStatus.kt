// The overlay's hold-to-talk key, as the OS took it (#62).
package dev.supermux.desktop.overlay

import kotlinx.coroutines.flow.MutableStateFlow

/** What Settings says about the hold-to-talk key: the combo the OS refused (another app holds it), or null. */
internal object TalkKeyStatus {
    val refused = MutableStateFlow<KeyCombo?>(null)

    /** Wayland: what its GlobalShortcuts portal made of the key (null until asked, and off Wayland). */
    val wayland = MutableStateFlow<WaylandHotKey.Outcome?>(null)

    /** Counts the times the key in use was picked again in Settings: each time it is set up anew. */
    val picks = MutableStateFlow(0)
}
