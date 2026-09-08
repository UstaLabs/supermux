package dev.supermux.ios

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signals that flow FROM Swift INTO the Compose root, as state Swift pushes rather than
 * questions Kotlin asks.
 *
 * [IosBridge] is the other direction — Kotlin asking Swift to present a sheet or record a clip —
 * and these two do not belong on it. A `StateFlow` property on a Kotlin interface is not something
 * Swift can reasonably IMPLEMENT (it would have to construct Kotlin flow objects), whereas calling
 * a setter is natural on both sides. So the app delegate calls in here and the composition
 * collects; nothing has to be polled and nothing has to be implemented twice.
 *
 * A single process-wide object, deliberately: `SupermuxApp.swift`'s scene-phase observer and
 * `onOpenURL` are application-level, they fire before and after any particular view controller
 * exists, and there is exactly one Compose root in this app.
 */
object IosAppState {

    private val _foreground = MutableStateFlow(true)

    /**
     * Whether the app is in front.
     *
     * The shared shell suppresses "viewing presence" while the app is backgrounded, which is what
     * keeps the broker sending pushes for a chat the user is not actually looking at. Starts `true`
     * because the only way this object is first read is from a launching, foregrounded app; Swift
     * corrects it on the first scene-phase change.
     */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }

    private val _openedUrl = MutableStateFlow<String?>(null)

    /**
     * The most recent `supermux://` URL the app was opened with, or null.
     *
     * Consumed by the pairing gate, which is why it lands in H2 rather than waiting for the deep
     * link work in H3: a `supermux://pair?...` link is one of the two ways a device gets paired at
     * all, and the onboarding flow takes it as `initialDeepLink`. [consumeOpenedUrl] clears it so a
     * later recomposition cannot re-enter pairing with a link the user already used.
     */
    val openedUrl: StateFlow<String?> = _openedUrl.asStateFlow()

    fun setOpenedUrl(url: String?) {
        _openedUrl.value = url
    }

    fun consumeOpenedUrl() {
        _openedUrl.value = null
    }
}
