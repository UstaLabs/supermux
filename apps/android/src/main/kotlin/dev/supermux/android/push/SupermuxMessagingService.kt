package dev.supermux.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.supermux.android.DevConfig
import dev.supermux.android.MainActivity
import dev.supermux.auth.SecureTokenStore
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.net.BrokerApi
import dev.supermux.push.openSealedPush
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Firebase Cloud Messaging entry point for native push.
 *
 * Lifecycle of a notification (relay sends **data-only** messages; see `src/relay/fcm.ts`,
 * `data: { d: <ciphertext> }`):
 *
 *  1. [onNewToken] — a fresh FCM token. If the app is paired (has baseUrl + token):
 *       a. `relayUrl = BrokerApi(...).pushRelayUrl()`   (skip if null — relay not configured)
 *       b. `registerPushTokenWithRelay(relayUrl, "android", fcmToken)` — asks the relay to
 *          push a *bootstrap* data message back to this device carrying a routingToken.
 *  2. [onMessageReceived] with a BOOTSTRAP `d` (`{"kind":"bootstrap","routingToken":...}`):
 *       c. `registerPushDevice("android", routingToken, publicKeyB64Url)` — tells the broker
 *          which routingToken + pubkey to seal future notifications for.
 *  3. [onMessageReceived] with a SEALED `d`:
 *       d. `openSealedPush(d, privatePkcs8B64)` → parse `{session, sessionId?, text}` → notify.
 *
 * All network/crypto runs on [scope] (a process-lived IO scope); FCM grants a short wake
 * window per message, which is ample for one HTTP round-trip.
 */
class SupermuxMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One client per service instance (mirrors AppViewModel's `HttpClient(CIO)`).
    private val http: HttpClient by lazy { HttpClient(CIO) }

    private val keypair: PushKeypair by lazy { PushKeypair(applicationContext) }

    override fun onNewToken(token: String) {
        Log.i(TAG, "onNewToken: registering with relay")
        // Ensure the shared SecureTokenStore has a context even if MainActivity never ran
        // (FCM can start the process directly into this service).
        SecureTokenStoreContext.init(applicationContext)
        scope.launch { registerWithRelay(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data["d"]
        if (d.isNullOrEmpty()) {
            Log.w(TAG, "data message without 'd' field; ignoring")
            return
        }
        SecureTokenStoreContext.init(applicationContext)
        when (val routed = PushRouter.classify(d)) {
            is PushRouter.Routed.Bootstrap -> {
                Log.i(TAG, "bootstrap message: registering device with broker")
                scope.launch { registerDeviceWithBroker(routed.routingToken) }
            }
            is PushRouter.Routed.Sealed -> {
                handleSealed(routed.blob)
            }
        }
    }

    /** Steps 1a–1b: resolve the relay URL from the broker and hand it our FCM token. */
    private suspend fun registerWithRelay(fcmToken: String) {
        val creds = pairedCreds() ?: run {
            Log.i(TAG, "not paired (no baseUrl/token); skipping relay registration")
            return
        }
        try {
            val api = BrokerApi(creds.baseUrl, creds.token, http)
            val relayUrl = api.pushRelayUrl()
            if (relayUrl.isNullOrBlank()) {
                Log.i(TAG, "broker has no relayUrl; native push not configured")
                return
            }
            api.registerPushTokenWithRelay(relayUrl, PLATFORM, fcmToken)
            Log.i(TAG, "registered FCM token with relay; awaiting bootstrap push")
        } catch (e: Throwable) {
            Log.w(TAG, "relay registration failed: ${e.message}")
        }
    }

    /** Step 2c: register this device (routingToken + pubkey) with the broker. */
    private suspend fun registerDeviceWithBroker(routingToken: String) {
        val creds = pairedCreds() ?: run {
            Log.w(TAG, "bootstrap arrived but app is not paired; cannot register device")
            return
        }
        try {
            val pubkey = keypair.publicKeyB64Url()
            BrokerApi(creds.baseUrl, creds.token, http)
                .registerPushDevice(PLATFORM, routingToken, pubkey)
            Log.i(TAG, "device registered with broker")
        } catch (e: Throwable) {
            Log.w(TAG, "broker device registration failed: ${e.message}")
        }
    }

    /** Step 3d: decrypt a sealed blob and post a notification. */
    private fun handleSealed(blob: String) {
        val plaintext = try {
            openSealedPush(blob, keypair.privatePkcs8B64())
        } catch (e: Throwable) {
            Log.w(TAG, "failed to decrypt sealed push: ${e.message}")
            return
        }
        val note = PushRouter.parseNotification(plaintext)
        if (note == null) {
            Log.w(TAG, "decrypted payload did not parse as a notification")
            return
        }
        postNotification(note)
    }

    private fun postNotification(note: PushRouter.Notification) {
        ensureChannel(this)

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            note.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            this,
            (note.sessionId ?: note.session).hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(note.session)
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (!hasPostPermission(this)) {
            // API 33+ without the runtime grant: posting is a silent no-op. The runtime
            // request belongs to the UI (see PushPermission); we just skip here.
            Log.i(TAG, "POST_NOTIFICATIONS not granted; skipping notify")
            return
        }
        // Distinct id per session so concurrent sessions don't overwrite each other.
        val id = (note.sessionId ?: note.session).hashCode().absoluteValue
        NotificationManagerCompat.from(this).notify(id, builder.build())
    }

    private fun pairedCreds(): Creds? {
        val token = SecureTokenStore().load()?.takeIf { it.isNotBlank() } ?: return null
        val baseUrl = DevConfig.brokerUrl().takeIf { it.isNotBlank() } ?: return null
        return Creds(baseUrl, token)
    }

    private data class Creds(val baseUrl: String, val token: String)

    override fun onDestroy() {
        super.onDestroy()
        runCatching { http.close() }
    }

    companion object {
        private const val TAG = "SupermuxFCM"
        private const val PLATFORM = "android"

        const val CHANNEL_ID = "sessions"
        const val CHANNEL_NAME = "Sessions"
        const val EXTRA_SESSION_ID = "supermux.sessionId"

        /** Create the "Sessions" notification channel (idempotent; API 26+). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Agent session updates" }
            mgr.createNotificationChannel(channel)
        }

        /** True if notifications may be posted (always true below API 33). */
        fun hasPostPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
