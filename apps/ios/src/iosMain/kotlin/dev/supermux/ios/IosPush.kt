// iOS's `Platform.push` (cluster H3): the shared `PushRegistrar` over Swift's existing
// `PushManager`, plus the tap route from a delivered notification back into the shared shell.
package dev.supermux.ios

import dev.supermux.ui.platform.PushRegistrar

/**
 * APNs registration as a [PushRegistrar] — the counterpart of Android's `AndroidPushRegistrar`.
 *
 * Everything real is Swift's `PushManager`, unchanged from the SwiftUI shell: authorisation,
 * `registerForRemoteNotifications()`, the relay handshake, the push keypair the notification
 * service extension shares through the Keychain, and the delivered-notification bookkeeping in
 * `PushGroupState`. This class exists to let the shared shell drive that flow without naming it.
 *
 * Two members are deliberately no-ops, and neither is unfinished work:
 *
 *  - [ensureChannel] has no iOS counterpart. A notification CHANNEL is an Android 8 concept (the
 *    user-visible importance/sound group a notification is filed under); iOS's nearest thing is a
 *    notification CATEGORY, which decides which content extension renders an expanded push, and
 *    `PushAppDelegate.handleLaunch()` registers ours before any notification can arrive — earlier
 *    than a Compose composition could.
 *  - [requestPermission] cannot be separated from registration here. `UNUserNotificationCenter`'s
 *    authorisation request is the FIRST step of `registerIfPaired()`, and iOS shows that prompt
 *    exactly once per install: asking early, on a launch where the app may not even be paired,
 *    would spend the one prompt the app gets on a moment when a refusal costs the user nothing to
 *    give. Android splits them because its POST_NOTIFICATIONS grant is re-requestable.
 */
class IosPushRegistrar(private val bridge: IosBridge) : PushRegistrar {

    override fun ensureChannel() = Unit

    override fun requestPermission() = Unit

    override fun registerIfPaired() = onMainThread { bridge.registerPushIfPaired() }

    override fun cancelForSession(sessionId: String) =
        onMainThread { bridge.cancelNotificationsFor(sessionId) }
}
