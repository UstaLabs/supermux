package dev.supermux.ios

import dev.supermux.media.FileMediaStore
import dev.supermux.media.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The app's one attachment cache, in `Library/Caches` — never backed up, and iOS may reclaim it
 * when the device runs low on storage (every file downloads again from its host when next viewed).
 */
object IosMedia {
    val cache: MediaCache by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory.toULong(), NSUserDomainMask.toULong(), true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        MediaCache(
            FileMediaStore(
                "$caches/supermux-media",
                fileUri = { NSURL.fileURLWithPath(it).absoluteString ?: "file://$it" },
                io = Dispatchers.IO,
            ),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
