// #117 W1: which applet an "Applet" widget shows. Its setup is optional (a widget placed from the picker arrives
// saying "Choose an applet"), so this opens from that first tap, or from the Home screen's "Settings" on the widget
// (reconfigurable). Lists the host's applets from the widget snapshot, asking the broker first when it is stale.
package dev.supermux.android.applets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AppletWidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget as it was (asking again on its next tap).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val prefs = UiPrefs(AndroidSettingsStore(applicationContext))
        val (appearanceSeed, textScaleSeed) = runBlocking { prefs.appearance(AppearanceMode.SYSTEM).first() to prefs.textScale.first() }
        setContent {
            val appearance by prefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by prefs.textScale.collectAsState(textScaleSeed)
            var host by remember { mutableStateOf(AppletWidgetStore.active(applicationContext)) }
            var loading by remember { mutableStateOf(host == null) }
            val icons = remember(host) { mutableMapOf<String, Bitmap?>() }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                AppletWidgetStore.refreshIfStale(applicationContext, force = host == null)
                host = AppletWidgetStore.active(applicationContext)
                loading = false
            }
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = prefs) {
                Scaffold(
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                    topBar = { TopAppBar(title = { Text(localized(Str.applets_widget_choose)) }) },
                ) { padding ->
                    val h = host
                    val list = h?.applets.orEmpty().sortedWith(compareBy({ it.pin ?: Int.MAX_VALUE }, { it.title.lowercase() }))
                    when {
                        loading && list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                localized(if (h == null) Str.applets_widget_signed_out else Str.applets_widget_none),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                            items(list, key = { it.name }) { a ->
                                val icon = icons.getOrPut(a.name) { AppletWidgetStore.icon(applicationContext, h!!.hostId, a.name) }
                                    ?: AppletIcons.bitmap(null, a.title, 144, inset = 0f, circle = true, letter = 0.42f)
                                val line = a.summary?.let { s -> listOfNotNull(s.value, s.label).joinToString(" ").takeIf { it.isNotBlank() } }
                                ListItem(
                                    leadingContent = { Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) },
                                    headlineContent = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = if (line != null) ({ Text(line, maxLines = 1) }) else null,
                                    modifier = Modifier.testTag("widget_pick_${a.name}").clickable {
                                        scope.launch {
                                            withContext(Dispatchers.Default) { setAppletWidget(applicationContext, appWidgetId, h!!.hostId, a.name) }
                                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                            finish()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
