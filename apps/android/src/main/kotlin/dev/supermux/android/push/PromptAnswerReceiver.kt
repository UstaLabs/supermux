package dev.supermux.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.supermux.android.host.HostStores
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.net.BrokerApi
import dev.supermux.state.FleetStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * #143: a permission prompt's Allow / Deny, tapped on its notification. Answers the broker that sent it over HTTP
 * (`POST /sessions/<id>/requests/<requestId>`) with that host's device token, so it works with the app closed. Allow is
 * posted only behind the device unlock (its action sets `setAuthenticationRequired`); Deny goes at once. The
 * notification then says what happened, or that the prompt was already answered elsewhere.
 */
class PromptAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID) ?: return
        val hostId = intent.getStringExtra(EXTRA_HOST_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val chosen = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val pending = goAsync()
        scope.launch {
            try {
                SecureTokenStoreContext.init(app)
                val creds = credsFor(app, hostId)
                val outcome = if (creds == null) {
                    "Couldn't answer: this host isn't paired. Open Supermux."
                } else {
                    HttpClient(CIO).use { http ->
                        runCatching { BrokerApi(creds.baseUrl, creds.token, http).answerRequest(sessionId, requestId, optionId) }
                            .fold(
                                onSuccess = { "Answered: $chosen" },
                                onFailure = { e ->
                                    Log.w(TAG, "answer failed: ${e.message}")
                                    "Couldn't answer (already answered, or the host is unreachable). Open Supermux."
                                },
                            )
                    }
                }
                update(app, notificationId, title, outcome)
            } finally {
                pending.finish()
            }
        }
    }

    private fun update(context: Context, id: Int, title: String, text: String) {
        if (!SupermuxMessagingService.hasPostPermission(context)) return
        val note = NotificationCompat.Builder(context, SupermuxMessagingService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setGroup(SupermuxMessagingService.GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, note)
    }

    companion object {
        private const val TAG = "SupermuxPrompt"
        const val ACTION_ANSWER = "dev.supermux.android.ANSWER_PROMPT"
        const val EXTRA_SESSION_ID = "supermux.prompt.sessionId"
        const val EXTRA_REQUEST_ID = "supermux.prompt.requestId"
        const val EXTRA_OPTION_ID = "supermux.prompt.optionId"
        const val EXTRA_HOST_ID = "supermux.prompt.hostId"
        const val EXTRA_NOTIFICATION_ID = "supermux.prompt.notificationId"
        const val EXTRA_TITLE = "supermux.prompt.title"
        const val EXTRA_LABEL = "supermux.prompt.label"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The paired host that sent the push; with no hostId (an older broker), the only paired host, if one. */
        internal fun credsFor(context: Context, hostId: String?): SupermuxMessagingService.Companion.Creds? {
            val hosts = runCatching { HostStores.store(context).list() }.getOrDefault(emptyList())
            val host = if (hostId != null) hosts.firstOrNull { it.hostId == hostId } else hosts.singleOrNull()
            if (host != null) {
                val url = FleetStore.effectiveUrl(host)?.takeIf { it.isNotBlank() } ?: return null
                val token = host.token.takeIf { it.isNotBlank() } ?: return null
                return SupermuxMessagingService.Companion.Creds(url, token)
            }
            if (hostId != null) return null
            return SupermuxMessagingService.resolveAllPairedCreds(context).singleOrNull()
        }
    }
}
