package dev.supermux.android.platform

import android.content.Context
import android.net.Uri
import dev.supermux.media.FileMediaStore
import dev.supermux.media.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The app's one attachment cache, per PROCESS: `AppViewModel` is per activity, and a second window
 * must share the memory tier and see a Clear. Files live in `cacheDir/media`, which the OS may
 * reclaim when storage runs low and "Clear cache" in the app's system settings empties.
 */
object AndroidMedia {
    @Volatile private var instance: MediaCache? = null

    fun cache(context: Context): MediaCache = instance ?: synchronized(this) {
        instance ?: MediaCache(
            FileMediaStore(
                File(context.applicationContext.cacheDir, "media").path,
                fileUri = { Uri.fromFile(File(it)).toString() },
                io = Dispatchers.IO,
            ),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { instance = it }
    }
}
