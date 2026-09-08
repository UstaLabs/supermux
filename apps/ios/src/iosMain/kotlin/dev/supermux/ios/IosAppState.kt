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
     *
     * "In front" means VISIBLE, not focused: Swift maps `scenePhase != .background`, matching
     * Android's ON_START/ON_STOP rather than desktop's window focus. A phone loses focus for
     * things that leave the chat perfectly readable — Notification Center pulled halfway down, a
     * call banner — and reporting "away" for those would have the broker push a notification for
     * the message already on screen.
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

    private val _pendingPushSessionId = MutableStateFlow<String?>(null)

    /**
     * The chat a tapped notification was about, or null.
     *
     * Set by `PushAppDelegate`'s `didReceive response:` handler — the same place that feeds
     * `PushRouter.pendingSessionId` for the SwiftUI shell, which under `COMPOSE_SHELL` nobody
     * reads any more (only the deleted-path `RootView` ever did). It is a STATE flow rather than
     * an event channel deliberately: a tap can be what LAUNCHES the app, in which case Swift sets
     * this before the Compose root exists, and the collector that appears milliseconds later must
     * still see it.
     *
     * The shared shell consumes it via `resolvePushTap`, and [consumePendingPushSessionId] clears
     * it so a later recomposition cannot yank the user back to a chat they have since left.
     */
    val pendingPushSessionId: StateFlow<String?> = _pendingPushSessionId.asStateFlow()

    fun setPendingPushSessionId(id: String?) {
        _pendingPushSessionId.value = id
    }

    fun consumePendingPushSessionId() {
        _pendingPushSessionId.value = null
    }

    private val _pendingPairLink = MutableStateFlow<String?>(null)

    /**
     * A `supermux://pair` / `https://…/pair?t=` link that arrived while the app is ALREADY paired.
     *
     * The unpaired case is [openedUrl], which the pairing gate takes as its `initialDeepLink`. A
     * link that arrives afterwards means "add this second host", so it goes down a different path:
     * `IosPlatform.pendingScans()` publishes it, the shared `AddHostScreen` collects that flow and
     * claims whatever it receives, and `MainViewController` navigates to that screen. Which is why
     * it is a state flow and not an event: the screen has to be composed before it can collect,
     * and a plain event emitted at navigation time would land before there was anything listening.
     */
    val pendingPairLink: StateFlow<String?> = _pendingPairLink.asStateFlow()

    fun setPendingPairLink(url: String?) {
        _pendingPairLink.value = url
    }

    fun consumePendingPairLink() {
        _pendingPairLink.value = null
    }
}
