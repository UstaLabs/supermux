// #165: a widget button or toggle. It runs the applet's action through the paired host, from the Home screen, with no
// app on screen; the widget redraws from the answer at once (the applet's new widget). An action the applet marks
// "confirm" opens the applet instead, where it can ask.
package dev.supermux.android.applets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class WidgetActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val hostId = parameters[HOST] ?: return
        val applet = parameters[APPLET] ?: return
        val action = parameters[ACTION] ?: return
        if (parameters[CONFIRM] == true) {
            runCatching { context.startActivity(AppletActivity.intent(context, hostId, applet, parameters[TITLE] ?: applet)) }
            return
        }
        val args = parameters[ARGS]?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val outcome = withTimeoutOrNull(15_000) {
            HttpClient(CIO).use { http ->
                val api = AppletWidgetStore.hostApi(context, hostId, http) ?: return@use Result.failure(IllegalStateException(localized(Str.applets_widget_signed_out)))
                runCatching { api.appletWidgetAction(applet, action, args) }
            }
        } ?: Result.failure(IllegalStateException(localized(Str.applets_widget_host_unreachable)))
        outcome.onSuccess { AppletWidgetStore.putWidget(context, hostId, applet, it) }
            .onFailure { e ->
                Log.w(TAG, "widget action $applet/$action failed: ${e.message}") // i18n-ok: log line
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, localized(Str.applets_widget_action_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                // A toggle showed its new state already: draw the truth again.
                AppletWidgetStore.updateWidgets(context)
            }
    }

    companion object {
        private const val TAG = "AppletWidgets"
        val HOST = ActionParameters.Key<String>("host")
        val APPLET = ActionParameters.Key<String>("applet")
        val TITLE = ActionParameters.Key<String>("title")
        val ACTION = ActionParameters.Key<String>("action")
        val ARGS = ActionParameters.Key<String>("args")
        val CONFIRM = ActionParameters.Key<Boolean>("confirm")
    }
}
