// #167: what the share extension (apps/iosApp/SupermuxShare) needs from the app: the chats to offer, as
// share/targets.json in the App Group (ShareCore.swift reads it), and every paired host's address and key in the
// shared keychain group (the same item the applet widgets read).
package dev.supermux.ios

import dev.supermux.auth.WidgetCredentials
import dev.supermux.host.IosHostStores
import dev.supermux.share.ShareTarget
import dev.supermux.share.ShareTargets
import dev.supermux.state.FleetStore
import dev.supermux.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal object IosShareTargets {
    private val json = Json { encodeDefaults = true }
    private var last: String? = null

    fun publish() {
        val hosts = runCatching { IosHostStores.store().list() }.getOrDefault(emptyList())
        for (h in hosts) {
            val url = FleetStore.effectiveUrl(h) ?: continue
            if (h.token.isNotBlank()) runCatching { WidgetCredentials.save(h.recordId, url, h.token) }
        }
        val snaps = IosHostStores.snapshotStore().all().filter { s -> hosts.any { it.recordId == s.recordId } }
        val targets = ShareTargets.from(snaps, hosts.associate { it.recordId to it.displayName })
        val text = json.encodeToString(ListSerializer(ShareTarget.serializer()), targets)
        if (text == last) return
        val root = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(IosAppletWidgetStore.APP_GROUP)?.path ?: return
        val dir = "$root/share"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        if (text.encodeToByteArray().toNSData().writeToFile("$dir/targets.json", atomically = true)) last = text
    }
}
