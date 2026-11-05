// The overlay's hold-to-talk key, as the OS took it (#62).
package dev.supermux.desktop.overlay

import kotlinx.coroutines.flow.MutableStateFlow

/** What Settings says about the hold-to-talk key: the combo the OS refused (another app holds it), or null. */
internal object TalkKeyStatus {
    val refused = MutableStateFlow<KeyCombo?>(null)
}
