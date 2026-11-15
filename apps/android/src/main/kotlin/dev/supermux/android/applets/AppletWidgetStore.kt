// #117 W1: what the Home screen widgets draw from. A widget is a snapshot, never a live view: the app writes each host's
// applets here (titles, red dots, summaries, icons drawn round) whenever it sees them change, and the widgets read
// them back. When the snapshot is older than a quarter of an hour, a widget update (the system's half-hourly one, or a
// push) asks the broker itself, with the paired host's own credentials — the same ones push registration uses.
package dev.supermux.android.applets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.glance.appwidget.updateAll
import dev.supermux.android.host.HostStores
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.net.AppletDto
import dev.supermux.net.AppletSummaryDto
import dev.supermux.net.BrokerApi
import dev.supermux.state.FleetStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One applet as a widget shows it. */
@Serializable
internal data class WidgetApplet(
    val name: String,
    val title: String,
    /** A secret to fill in or renew: the red dot. */
    val needs: Boolean = false,
    val pin: Int? = null,
    val summary: AppletSummaryDto? = null,
    /** When its icon file was drawn ([AppletDto.updatedAt]); a newer applet draws it again. */
    val iconAt: String = "",
)

/** One host's applets, as last seen. */
@Serializable
internal data class WidgetHost(val hostId: String, val at: Long, val applets: List<WidgetApplet>) {
    /** The grid's applets: the pinned ones by their place, as the app's sidebar on a phone (the first 8). */
    fun pinned(): List<WidgetApplet> = applets.filter { it.pin != null }.sortedBy { it.pin }.take(8)
}

internal object AppletWidgetStore {
    private const val TAG = "AppletWidgets"
    private const val STALE_MS = 15 * 60_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private fun dir(context: Context) = File(context.filesDir, "applet-widgets").also { it.mkdirs() }
    private fun hostFile(context: Context, hostId: String) = File(dir(context), "host-${key(hostId)}.json")
    private fun activeFile(context: Context) = File(dir(context), "active")
    private fun iconFile(context: Context, hostId: String, name: String) = File(dir(context), "icon-${key("$hostId/$name")}.png")

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** The host the grid shows: the app's active one, else any host seen. */
    fun active(context: Context): WidgetHost? {
        val id = runCatching { activeFile(context).readText().trim() }.getOrNull()
        return id?.let { host(context, it) } ?: hosts(context).maxByOrNull { it.at }
    }

    fun host(context: Context, hostId: String): WidgetHost? =
        runCatching { json.decodeFromString(WidgetHost.serializer(), hostFile(context, hostId).readText()) }.getOrNull()

    private fun hosts(context: Context): List<WidgetHost> =
        dir(context).listFiles { f -> f.name.startsWith("host-") }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(WidgetHost.serializer(), it.readText()) }.getOrNull() }

    /** The applet's round icon, as drawn when the snapshot was written; null before the first one. */
    fun icon(context: Context, hostId: String, name: String): Bitmap? =
        iconFile(context, hostId, name).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    /**
     * Writes [hostId]'s applets ([active]: the app's host picker is on it) and redraws the widgets when anything a
     * widget shows moved. [icon] fetches an icon's bytes; it runs only for applets whose icon changed.
     */
    suspend fun save(context: Context, hostId: String, applets: List<AppletDto>, active: Boolean, redraw: Boolean = true, icon: suspend (AppletDto) -> ByteArray?) {
        val changed = lock.withLock {
            withContext(Dispatchers.IO) {
                val before = host(context, hostId)
                val drawn = before?.applets?.associate { it.name to it.iconAt }.orEmpty()
                val next = applets.filter { it.status != "missing" }.map { a ->
                    val title = a.title.ifBlank { a.name }
                    val file = iconFile(context, hostId, a.name)
                    val iconAt = a.updatedAt + "|" + (a.iconUrl ?: "") + "|" + title
                    if (drawn[a.name] != iconAt || !file.exists()) {
                        val bytes = if (a.iconUrl != null) runCatching { icon(a) }.getOrNull() else null
                        runCatching {
                            val bmp = AppletIcons.bitmap(bytes, title, 144, inset = 0f, circle = true, letter = 0.42f)
                            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bmp.recycle()
                        }
                    }
                    WidgetApplet(a.name, title, a.needsYou.isNotEmpty(), a.pin, a.summary, iconAt)
                }
                val host = WidgetHost(hostId, System.currentTimeMillis(), next)
                hostFile(context, hostId).writeText(json.encodeToString(WidgetHost.serializer(), host))
                val wasActive = runCatching { activeFile(context).readText().trim() }.getOrNull()
                if (active && wasActive != hostId) activeFile(context).writeText(hostId)
                // Gone applets' icons go too.
                val keep = next.map { iconFile(context, hostId, it.name).name }.toSet()
                before?.applets?.filter { iconFile(context, hostId, it.name).name !in keep }?.forEach { iconFile(context, hostId, it.name).delete() }
                before?.applets != next || (active && wasActive != hostId)
            }
        }
        if (changed && redraw) updateWidgets(context)
    }

    /** Redraws every supermux widget on the Home screen from the snapshot. */
    suspend fun updateWidgets(context: Context) {
        runCatching { AppletsGridWidget().updateAll(context) }.onFailure { Log.w(TAG, "grid update failed", it) }
        runCatching { AppletWidget().updateAll(context) }.onFailure { Log.w(TAG, "applet update failed", it) }
    }

    /**
     * Asks every paired host for its applets when the snapshot is older than a quarter of an hour (or [force]),
     * within [timeoutMs]: the widgets then draw what's true now even when the app hasn't run for hours.
     */
    suspend fun refreshIfStale(context: Context, force: Boolean = false, timeoutMs: Long = 15_000) {
        val newest = hosts(context).maxOfOrNull { it.at } ?: 0L
        if (!force && System.currentTimeMillis() - newest < STALE_MS) return
        SecureTokenStoreContext.init(context.applicationContext)
        val paired = runCatching { HostStores.store(context).list() }.getOrDefault(emptyList())
        if (paired.isEmpty()) return
        val activeId = runCatching { activeFile(context).readText().trim() }.getOrNull()
        withTimeoutOrNull(timeoutMs) {
            HttpClient(CIO).use { http ->
                for (h in paired) {
                    val url = FleetStore.effectiveUrl(h) ?: continue
                    if (h.token.isBlank()) continue
                    val api = BrokerApi(url, h.token, http)
                    val applets = runCatching { api.applets() }.getOrNull() ?: continue
                    // No redraw from here: the widget that asked draws next, from what was just written.
                    save(context, h.recordId, applets, active = activeId == null || activeId == h.recordId, redraw = false) { api.appletIconBytes(it) }
                }
            }
        }
    }
}
