// Cluster G1: Android's actual behind `Platform.push`. The FCM service, the keypair, the relay
// registration and the POST_NOTIFICATIONS request all stay in this package; this is the seam the
// entry point (and, from G8, the shared root) drives them through.
package dev.supermux.android.push

import android.content.Context
import androidx.activity.ComponentActivity
import dev.supermux.ui.platform.PushRegistrar

/**
 * FCM registration as a [PushRegistrar].
 *
 * [requestPermission] needs a live [ComponentActivity] (it registers an activity-result launcher),
 * so it runs only when this registrar was built from one — `MainActivity` is exactly that, and a
 * non-activity context degrades to a no-op rather than crashing. Everything else is
 * application-scoped and safe to call from anywhere, any number of times.
 */
class AndroidPushRegistrar(private val context: Context) : PushRegistrar {
    private val appContext = context.applicationContext

    override fun ensureChannel() {
        SupermuxMessagingService.ensureChannel(appContext)
    }

    override fun requestPermission() {
        (context as? ComponentActivity)?.let { PushPermission.request(it) }
    }

    override fun registerIfPaired() {
        SupermuxMessagingService.registerIfPaired(appContext)
    }

    override fun cancelForSession(sessionId: String) {
        SupermuxMessagingService.cancelForSession(appContext, sessionId)
    }
}
