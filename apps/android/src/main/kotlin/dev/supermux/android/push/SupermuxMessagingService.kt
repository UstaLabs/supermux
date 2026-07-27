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
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.supermux.android.MainActivity
import dev.supermux.android.host.HostConnections
import dev.supermux.android.host.HostStores
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
 *  1. [registerIfPaired] (app launch + after pair) or [onNewToken] — an FCM token. If paired:
 *       a. `relayUrl = BrokerApi(...).pushRelayUrl()`   (skip if null — relay not configured)
 *       b. `registerPushTokenWithRelay(relayUrl, "android", fcmToken)` — returns
 *          `routingToken` over HTTP (and best-effort bootstrap FCM).
 *       c. Immediately `registerPushDevice("android", routingToken, publicKeyB64Url)` on
 *          every paired broker — **do not wait for bootstrap FCM** (often dropped).
 *  2. [onMessageReceived] with a BOOTSTRAP `d` (legacy / redundant path):
 *       same as 1c if HTTP body lacked routingToken (old relay).
 *  3. [onMessageReceived] with a SEALED `d`:
 *       d. `openSealedPush(d, privatePkcs8B64)` → parse `{session, sessionId?, text}` → notify.
 *
 * All network/crypto runs on [scope] (a process-lived IO scope); FCM grants a short wake
 * window per message, which is ample for one HTTP round-trip.
 *
 * **Why [registerIfPaired] exists:** [onNewToken] often fires *before* pairing (first launch).
 * Without a cold-start/post-pair retry, registration is skipped forever until FCM rotates the
 * token. Parity with iOS `PushManager.registerIfPaired()`.
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
        scope.launch { registerWithRelayForAllHosts(applicationContext, token, http) }
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

    /** Step 1c / bootstrap: register this device (routingToken + pubkey) with every paired broker. */
    private suspend fun registerDeviceWithBroker(routingToken: String) {
        registerDeviceWithBrokers(applicationContext, routingToken, http, keypair)
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
            // Group every session's notifications under one stack (iMessage-style). Android
            // shows the summary below only once 2+ children exist; a lone notification stands
            // on its own.
            .setGroup(GROUP_KEY)

        if (!hasPostPermission(this)) {
            // API 33+ without the runtime grant: posting is a silent no-op. The runtime
            // request belongs to the UI (see PushPermission); we just skip here.
            Log.i(TAG, "POST_NOTIFICATIONS not granted; skipping notify")
            return
        }
        val nm = NotificationManagerCompat.from(this)
        // Distinct id per session so concurrent sessions don't overwrite each other.
        val id = (note.sessionId ?: note.session).hashCode().absoluteValue
        nm.notify(id, builder.build())
        // The summary carrier for the group. Cleared with the last child by
        // `cancelForSession` (Android usually auto-removes it, but not on every OEM).
        val summary = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        nm.notify(GROUP_SUMMARY_ID, summary)
    }

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

        /** All session notifications share this group so they stack together. */
        const val GROUP_KEY = "dev.supermux.sessions"
        /** Fixed id for the group summary carrier (never a real session's id). */
        const val GROUP_SUMMARY_ID = 424242

        /** Process-lived IO scope for registration kicked off outside the FCM service. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Cold-start / post-pair registration (parity with iOS `PushManager.registerIfPaired`).
         *
         * Fetches the current FCM token and registers it with the push relay for every paired
         * broker. Safe to call repeatedly; no-op when not paired. Must run after
         * [SecureTokenStoreContext.init] is possible (any app Context is fine).
         */
        fun registerIfPaired(context: Context) {
            val app = context.applicationContext
            SecureTokenStoreContext.init(app)
            val creds = resolveAllPairedCreds(app)
            if (creds.isEmpty()) {
                Log.i(TAG, "registerIfPaired: not paired; skip")
                return
            }
            ensureChannel(app)
            Log.i(TAG, "registerIfPaired: fetching FCM token for ${creds.size} host(s)")
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNullOrBlank()) {
                        Log.w(TAG, "registerIfPaired: empty FCM token")
                        return@addOnSuccessListener
                    }
                    appScope.launch {
                        // Short-lived client — this path is not service-scoped.
                        HttpClient(CIO).use { http ->
                            registerWithRelayForAllHosts(app, token, http)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "registerIfPaired: getToken failed: ${e.message}")
                }
        }

        /**
         * Resolve broker credentials for push registration.
         *
         * Prefer the multi-host store (source of truth after migration / fleet add). Fall back
         * to the legacy [SecureTokenStore] `(baseUrl, token)` written by onboarding — critical
         * right after pair, before [HostStores.migrateFromLegacyIfNeeded] runs.
         *
         * Never uses [dev.supermux.android.DevConfig.brokerUrl] (placeholder `CHANGE_ME` on
         * physical devices) — that was the root cause of silent Android push failure.
         */
        internal fun resolveAllPairedCreds(context: Context): List<Creds> {
            val fromHosts = runCatching {
                HostStores.store(context).list().mapNotNull { h ->
                    val url = HostConnections.effectiveUrl(h)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val tok = h.token.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Creds(url, tok)
                }
            }.getOrDefault(emptyList())
            if (fromHosts.isNotEmpty()) return fromHosts.distinctBy { it.baseUrl to it.token }

            val store = SecureTokenStore()
            val token = store.load()?.takeIf { it.isNotBlank() } ?: return emptyList()
            val baseUrl = store.loadBaseUrl()?.takeIf { it.isNotBlank() } ?: return emptyList()
            return listOf(Creds(baseUrl, token))
        }

        /**
         * Steps 1a–1c: resolve a push-relay URL from any paired broker, hand it our FCM
         * token, then POST `/push/device` to **every** paired broker with the returned
         * routingToken. The relay is **stateless** — routingToken seals (platform, fcmToken),
         * not a broker identity — so one /register is enough for the fleet.
         */
        internal suspend fun registerWithRelayForAllHosts(
            context: Context,
            fcmToken: String,
            http: HttpClient,
        ) {
            SecureTokenStoreContext.init(context)
            val all = resolveAllPairedCreds(context)
            if (all.isEmpty()) {
                Log.i(TAG, "not paired (no baseUrl/token); skipping relay registration")
                return
            }
            for (creds in all) {
                try {
                    val api = BrokerApi(creds.baseUrl, creds.token, http)
                    val relayUrl = api.pushRelayUrl()
                    if (relayUrl.isNullOrBlank()) {
                        Log.i(TAG, "broker has no relayUrl; native push not configured (${creds.baseUrl})")
                        continue
                    }
                    val routingToken = api.registerPushTokenWithRelay(relayUrl, PLATFORM, fcmToken)
                    Log.i(TAG, "registered FCM token with relay (${creds.baseUrl})")
                    if (!routingToken.isNullOrBlank()) {
                        // Primary path: do not depend on bootstrap FCM delivery.
                        registerDeviceWithBrokers(context, routingToken, http, PushKeypair(context))
                    } else {
                        Log.i(TAG, "relay omitted routingToken; waiting for bootstrap push (old relay?)")
                    }
                    return // one /register is enough (routingToken encodes the FCM token)
                } catch (e: Throwable) {
                    Log.w(TAG, "relay registration failed (${creds.baseUrl}): ${e.message}")
                }
            }
            Log.i(TAG, "no paired broker exposed a push relayUrl")
        }

        /** POST /push/device on every paired broker (HTTP path or bootstrap). */
        internal suspend fun registerDeviceWithBrokers(
            context: Context,
            routingToken: String,
            http: HttpClient,
            keypair: PushKeypair = PushKeypair(context),
        ) {
            val all = resolveAllPairedCreds(context)
            if (all.isEmpty()) {
                Log.w(TAG, "have routingToken but app is not paired; cannot register device")
                return
            }
            val pubkey = try {
                keypair.publicKeyB64Url()
            } catch (e: Throwable) {
                Log.w(TAG, "keypair unavailable: ${e.message}")
                return
            }
            for (creds in all) {
                try {
                    BrokerApi(creds.baseUrl, creds.token, http)
                        .registerPushDevice(PLATFORM, routingToken, pubkey)
                    Log.i(TAG, "device registered with broker (${creds.baseUrl})")
                } catch (e: Throwable) {
                    Log.w(TAG, "broker device registration failed (${creds.baseUrl}): ${e.message}")
                }
            }
        }

        /**
         * Clear a chat's notification when its screen is opened in the app (parity with iOS
         * `PushManager.clearDelivered`). Also drops the group summary once no per-session
         * notifications remain, so an empty group doesn't linger on OEMs that don't auto-remove it.
         */
        fun cancelForSession(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(sessionId.hashCode().absoluteValue)
            val sys = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val childrenLeft = runCatching {
                sys.activeNotifications.count { it.id != GROUP_SUMMARY_ID }
            }.getOrDefault(1)
            if (childrenLeft == 0) nm.cancel(GROUP_SUMMARY_ID)
        }

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

        internal data class Creds(val baseUrl: String, val token: String)
    }
}
