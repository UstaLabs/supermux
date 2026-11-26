// #117 W1: supermux's Home screen widgets. "Applets" is the pinned grid (4 × 2, as the phone's sidebar); "Applet" is one
// applet with the summary it gives ("31 · open · 2 waiting for testing"). A tap opens the applet in its own task, the
// same intent as the app and the pinned shortcut use, so it comes back to its Recents card. They draw from
// AppletWidgetStore's snapshot, in the system's widget colours (light and dark follow the phone).
package dev.supermux.android.applets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.supermux.android.MainActivity
import dev.supermux.android.R
import dev.supermux.net.AppletDto
import dev.supermux.net.WidgetApplet
import dev.supermux.net.WidgetDoc
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.session.AppletWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val HOST_KEY = stringPreferencesKey("applet_host")
private val NAME_KEY = stringPreferencesKey("applet_name")
/**
 * When the snapshot was last redrawn into this widget. Glance redraws a widget whose session is still open only when
 * its state changes, so every redraw ([redrawAll]) bumps this, and the content reads the snapshot inside composition.
 */
private val DRAWN_KEY = longPreferencesKey("snapshot_drawn")

/** Redraws every widget of [widget]'s kind from the snapshot. */
internal suspend fun redrawAll(context: Context, widget: GlanceAppWidget) {
    val now = System.currentTimeMillis()
    for (id in GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass)) {
        updateAppWidgetState(context, id) { it[DRAWN_KEY] = now }
        widget.update(context, id)
    }
}

/** "Applets": the pinned applets of the app's host. */
class AppletsGridWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppletWidgetStore.refreshIfStale(context)
        provideContent {
            // Read here, keyed on the redraw stamp: a redraw while the session is open recomposes with the new snapshot.
            val drawn = currentState<Preferences>()[DRAWN_KEY]
            val host = androidx.compose.runtime.remember(drawn) { AppletWidgetStore.active(context) }
            val pins = host?.pinned().orEmpty()
            val icons = androidx.compose.runtime.remember(drawn, host) { pins.associate { it.name to AppletWidgetStore.icon(context, host!!.hostId, it.name) } }
            GlanceTheme {
                Column(
                    WidgetFrame.padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth().clickable(openApp(context))) {
                        Image(ImageProvider(R.mipmap.ic_launcher_round), contentDescription = null, modifier = GlanceModifier.size(16.dp))
                        Spacer(GlanceModifier.width(6.dp))
                        Text(localized(Str.sessions_applets_title), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    if (host == null || pins.isEmpty()) {
                        Box(GlanceModifier.fillMaxSize().clickable(openApp(context)), contentAlignment = Alignment.Center) {
                            Text(
                                localized(if (host == null) Str.applets_widget_signed_out else Str.applets_widget_empty),
                                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                            )
                        }
                    } else {
                        for (row in pins.chunked(4)) {
                            Row(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                for (a in row) {
                                    AppletCell(context, host.hostId, a, icons[a.name], GlanceModifier.defaultWeight())
                                }
                                repeat(4 - row.size) { Spacer(GlanceModifier.defaultWeight()) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppletCell(context: Context, hostId: String, a: WidgetApplet, icon: android.graphics.Bitmap?, modifier: GlanceModifier) {
    Column(
        modifier.clickable(openApplet(context, hostId, a)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(50.dp), contentAlignment = Alignment.TopEnd) {
            Box(GlanceModifier.size(50.dp), contentAlignment = Alignment.Center) {
                AppletIconImage(context, a, icon, 46.dp)
            }
            if (a.needs) Image(ImageProvider(R.drawable.applet_widget_dot), contentDescription = localized(Str.applets_widget_needs_you), modifier = GlanceModifier.size(12.dp))
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(a.title, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurface))
    }
}

@Composable
private fun AppletIconImage(context: Context, a: WidgetApplet, icon: android.graphics.Bitmap?, size: Dp) {
    val bmp = icon ?: AppletIcons.bitmap(null, a.title, 144, inset = 0f, circle = true, letter = 0.42f)
    Image(ImageProvider(bmp), contentDescription = a.title, modifier = GlanceModifier.size(size))
}

/**
 * "Applet": one applet. #165: an applet with a widget document is drawn from its blocks, laid out for the shape the
 * launcher gives it (2×1 … 4×4); an older broker's summary is drawn as before, in large type.
 */
class AppletWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(WIDGET_SHAPES.keys)
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppletWidgetStore.refreshIfStale(context)
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        provideContent {
            val prefs = currentState<Preferences>()
            val hostId = prefs[HOST_KEY]
            val name = prefs[NAME_KEY]
            val drawn = prefs[DRAWN_KEY]
            val host = androidx.compose.runtime.remember(drawn, hostId) { hostId?.let { AppletWidgetStore.host(context, it) } }
            val a = host?.applets?.firstOrNull { it.name == name }
            GlanceTheme {
                when {
                    hostId == null || name == null -> Message(localized(Str.applets_widget_choose), chooseApplet(context, appWidgetId))
                    a == null -> Message(localized(Str.applets_widget_gone), chooseApplet(context, appWidgetId))
                    else -> {
                        val doc = androidx.compose.runtime.remember(drawn, a.widget) { WidgetDoc.parse(a.widget) }
                        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        if (doc != null) DocWidget(context, hostId, a, doc, AppletWidgetStore.icon(context, hostId, a.name), dark)
                        else OneApplet(context, hostId, a, AppletWidgetStore.icon(context, hostId, a.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun OneApplet(context: Context, hostId: String, a: WidgetApplet, icon: android.graphics.Bitmap?) {
    val s = a.summary
    Column(WidgetFrame.padding(14.dp).clickable(openApplet(context, hostId, a))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppletIconImage(context, a, icon, 22.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(a.title, maxLines = 1, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
            if (a.needs) {
                Spacer(GlanceModifier.width(4.dp))
                Image(ImageProvider(R.drawable.applet_widget_dot), contentDescription = localized(Str.applets_widget_needs_you), modifier = GlanceModifier.size(9.dp))
            }
        }
        if (s?.value != null) {
            Spacer(GlanceModifier.height(8.dp))
            Text(s.value!!, maxLines = 1, style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
        }
        s?.label?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant)) }
        s?.lines?.forEach { line ->
            Text(line, maxLines = 1, style = TextStyle(fontSize = 11.5.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        if (s == null) {
            // No summary: just the applet, big, to tap.
            Spacer(GlanceModifier.height(10.dp))
            Box(GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppletIconImage(context, a, icon, 56.dp) }
        }
    }
}

@Composable
private fun Message(text: String, action: Action) {
    Box(WidgetFrame.padding(14.dp).clickable(action), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }
}

/** The widget's own surface: the system's widget background and corner radius. */
private val WidgetFrame: GlanceModifier
    @Composable get() = widgetFrame()

@Composable
internal fun widgetFrame(): GlanceModifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
    .cornerRadius(24.dp) // Android 12+ rounds widgets itself to the launcher's radius; older ones draw it square

/** #165: the applet, opened at [at] (a widget row's item) when it gives one. */
internal fun openAppletAt(context: Context, hostId: String, a: WidgetApplet, at: String?): Action =
    actionStartActivity(AppletActivity.intent(context, hostId, a.name, a.title, at))

private fun openApplet(context: Context, hostId: String, a: WidgetApplet): Action =
    actionStartActivity(AppletActivity.intent(context, hostId, a.name, a.title))

private fun openApp(context: Context): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun chooseApplet(context: Context, appWidgetId: Int): Action =
    actionStartActivity(
        Intent(context, AppletWidgetConfigActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(android.net.Uri.parse("supermux-widget://$appWidgetId")),
    )

class AppletsGridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AppletsGridWidget()
}

class AppletWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AppletWidget()
}

/** Sets an "Applet" widget to [hostId]'s [name] and draws it. */
internal suspend fun setAppletWidget(context: Context, appWidgetId: Int, hostId: String, name: String) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    updateAppWidgetState(context, glanceId) { it[HOST_KEY] = hostId; it[NAME_KEY] = name }
    AppletWidget().update(context, glanceId)
}

/** The system's answer to an in-app pin: the new widget's id, with the applet it was pinned for. */
class AppletWidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val host = intent.getStringExtra(EXTRA_HOST) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { setAppletWidget(context.applicationContext, id, host, name) } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_HOST = "dev.supermux.applet.widget.HOST"
        const val EXTRA_NAME = "dev.supermux.applet.widget.NAME"
    }
}

/**
 * "Add widget" in an applet's menu: the system's own "Add widget?" dialog for an "Applet" widget, arriving set up for
 * that applet.
 */
class AndroidAppletWidgets(private val context: Context) : AppletWidgets {
    override val canPin: Boolean = true

    override suspend fun pin(hostId: String, applet: AppletDto): String? {
        val awm = AppWidgetManager.getInstance(context)
        if (!awm.isRequestPinAppWidgetSupported) return localized(Str.applets_widget_no_home)
        val callback = PendingIntent.getBroadcast(
            context,
            "$hostId/${applet.name}".hashCode(),
            Intent(context, AppletWidgetPinnedReceiver::class.java)
                .putExtra(AppletWidgetPinnedReceiver.EXTRA_HOST, hostId)
                .putExtra(AppletWidgetPinnedReceiver.EXTRA_NAME, applet.name),
            // Mutable: the system adds the new widget's id to it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val asked = runCatching { awm.requestPinAppWidget(ComponentName(context, AppletWidgetReceiver::class.java), null, callback) }
            .getOrElse { return it.message ?: localized(Str.applets_widget_no_home) }
        return if (asked) null else localized(Str.applets_widget_no_home)
    }
}
