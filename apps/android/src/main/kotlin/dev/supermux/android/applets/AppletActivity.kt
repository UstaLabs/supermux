// #117: an applet on Android opens as its own activity in its own task — its own card in Recents, with the applet's
// title — showing only the applet's page. The intent's data is the applet's address
// (supermux://applet/<host>/<name>); the manifest's documentLaunchMode="intoExisting" keys the task on it, so opening
// the same applet again (from the app, a shortcut or a widget) brings its card back instead of making another one
// ("always" made a new card per tap, verified on the Fold).
package dev.supermux.android.applets

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.supermux.android.AppFleet
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.net.AppletDto
import dev.supermux.ui.applets.AppletPage
import dev.supermux.ui.applets.appletUri
import dev.supermux.ui.applets.parseAppletUri
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.session.AppletOpener
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppletActivity : ComponentActivity() {
    private var shared: AppFleet.Shared? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { dev.supermux.ui.applets.AppletDeepLinks.note(it) }
        val target = intent?.data?.toString()?.let(::parseAppletUri)
        if (target == null) {
            finish()
            return
        }
        val (hostId, name) = target
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: name
        setTitle(title)
        setTaskDescription(ActivityManager.TaskDescription(title))
        enableEdgeToEdge()
        // The process's one fleet: the main window's connections, or a new one when only the applet runs.
        val fleet = AppFleet.acquire(this, this).also { shared = it }.fleet
        val prefs = UiPrefs(AndroidSettingsStore(applicationContext))
        // Blocking for the same reason MainActivity blocks: no first frame in the wrong theme.
        val (appearanceSeed, textScaleSeed) = runBlocking { prefs.appearance(AppearanceMode.SYSTEM).first() to prefs.textScale.first() }
        setContent {
            val appearance by prefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by prefs.textScale.collectAsState(textScaleSeed)
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = prefs) {
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppletPage(
                        fleet, hostId, name, Modifier.windowInsetsPadding(WindowInsets.safeDrawing), onOpenChat = ::openChat,
                        // What to update is Android's own: the System WebView, from the Play Store.
                        noEngine = dev.supermux.ui.i18n.localized(dev.supermux.ui.i18n.Str.applets_window_no_engine_android),
                    )
                }
            }
        }
    }

    /** The "Sent to …" link: the main window, at that chat (the push tap's way in). */
    private fun openChat(sessionId: String) {
        runCatching {
            startActivity(
                Intent(this, dev.supermux.android.MainActivity::class.java)
                    .putExtra(dev.supermux.android.push.SupermuxMessagingService.EXTRA_SESSION_ID, sessionId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    override fun onDestroy() {
        shared?.let { AppFleet.release(this) }
        shared = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "dev.supermux.applet.TITLE"

        fun intent(context: Context, hostId: String, name: String, title: String, at: String? = null): Intent =
            Intent(context, AppletActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(appletUri(hostId, name, at)))
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        /** Opens applets from [activity]: each in its own task, beside the app. */
        fun opener(activity: Activity) = AppletOpener { hostId, applet: AppletDto ->
            runCatching { activity.startActivity(intent(activity, hostId, applet.name, applet.title.ifBlank { applet.name })) }
        }
    }
}
