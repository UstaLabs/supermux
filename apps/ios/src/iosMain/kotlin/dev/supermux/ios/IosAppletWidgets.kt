// #117 W1 on the iPhone: the app keeps the widgets' snapshot in the App Group container (applet-widgets/), which the
// SwiftUI widget extension (SupermuxWidgets) reads, and asks WidgetKit to redraw through the bridge. A Control Center
// control or a Spotlight result leaves the applet it wants in the group's defaults ("applets.pendingOpen"): custom URL
// schemes are unreliable from a control, so the app picks it up when it comes to the front.
package dev.supermux.ios

import dev.supermux.net.AppletDto
import dev.supermux.net.WidgetHost
import dev.supermux.net.toWidgetApplets
import dev.supermux.net.widgetHostFile
import dev.supermux.ui.applets.parseAppletUri
import dev.supermux.ui.session.AppletWidgets
import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal object IosAppletWidgetStore {
    /** The App Group the app and its extensions share (Supermux.entitlements, SupermuxWidgets.entitlements). */
    const val APP_GROUP = "group.dev.supermux.app"
    private const val PENDING_KEY = "applets.pendingOpen"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** applet-widgets/ in the group container; null when the build has no App Group (then there are no widgets). */
    private fun dir(): String? {
        val root = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)?.path ?: return null
        val dir = "$root/applet-widgets"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()
    private fun write(path: String, bytes: ByteArray) = bytes.toNSData().writeToFile(path, atomically = true)

    /**
     * Writes [hostId]'s applets (the host picker's host, so also "active") and their icons; [reload] (WidgetKit's
     * reloadAllTimelines) runs only when something a widget shows moved.
     */
    suspend fun save(hostId: String, applets: List<AppletDto>, icon: suspend (AppletDto) -> ByteArray?, reload: () -> Unit) {
        val changed = lock.withLock {
            withContext(Dispatchers.IO) {
                val dir = dir() ?: return@withContext false
                val hostPath = "$dir/${widgetHostFile(hostId)}"
                val before = read(hostPath)?.let { runCatching { json.decodeFromString(WidgetHost.serializer(), it.decodeToString()) }.getOrNull() }
                val drawn = before?.applets?.associate { it.name to it.iconAt }.orEmpty()
                val byName = applets.associateBy { it.name }
                val next = applets.toWidgetApplets(hostId)
                for (w in next) {
                    val path = "$dir/${w.iconFile}"
                    if (drawn[w.name] == w.iconAt && NSFileManager.defaultManager.fileExistsAtPath(path)) continue
                    val a = byName.getValue(w.name)
                    // No icon file: the widget draws the lettered tile itself.
                    val bytes = if (a.iconUrl != null) runCatching { icon(a) }.getOrNull() else null
                    if (bytes != null) write(path, bytes) else NSFileManager.defaultManager.removeItemAtPath(path, null)
                }
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
                val host = WidgetHost(hostId, now, next)
                write(hostPath, json.encodeToString(WidgetHost.serializer(), host).encodeToByteArray())
                val activePath = "$dir/active"
                val wasActive = read(activePath)?.decodeToString()?.trim()
                if (wasActive != hostId) write(activePath, hostId.encodeToByteArray())
                val keep = next.map { it.iconFile }.toSet()
                before?.applets?.filter { it.iconFile.isNotEmpty() && it.iconFile !in keep }?.forEach {
                    NSFileManager.defaultManager.removeItemAtPath("$dir/${it.iconFile}", null)
                }
                before?.applets != next || wasActive != hostId
            }
        }
        if (changed) reload()
    }

    /** The applet a control or Spotlight asked for while the app was away, taken once: (host, name). */
    fun takePendingOpen(): Pair<String, String>? {
        val defaults = NSUserDefaults(suiteName = APP_GROUP)
        val uri = defaults.stringForKey(PENDING_KEY) ?: return null
        defaults.removeObjectForKey(PENDING_KEY)
        return parseAppletUri(uri)
    }
}

/** "Add widget" on an iPhone: only he can place one, so the menu shows how (AppletWidgetHowTo). */
internal object IosAppletWidgets : AppletWidgets {
    override val canPin: Boolean = false
    override suspend fun pin(hostId: String, applet: AppletDto): String? = null
}

/** The running iOS's major version (17 for 17.4). */
internal fun iosMajorVersion(): Int = platform.UIKit.UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0

/**
 * Whether this build can show applet widgets: iOS 18 or later, and the SupermuxWidgets extension really is in the app
 * (a signed build made without the extension's profile drops it; then "Add widget" would explain widgets that aren't there).
 */
internal fun iosHasAppletWidgets(): Boolean {
    if (iosMajorVersion() < 18) return false
    val plugins = platform.Foundation.NSBundle.mainBundle.builtInPlugInsPath ?: return false
    return platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath("$plugins/SupermuxWidgets.appex")
}
