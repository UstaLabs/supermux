package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.desktop.DesktopApp
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Where WebKit keeps an app's profiles on Linux, and who may: each app has folders of its own, and one process of it
 * at a time has a profile's folder, as WebKit's network process isn't made to share its store with another's.
 * - `$XDG_DATA_HOME/dev.supermux.webview/wpe/<app>/default` (and `$XDG_CACHE_HOME/…/<app>`): the [WebProfile.Default].
 * - `…/<app>/profiles/<id>`: a [WebProfile.Named].
 * - [WebProfile.Ephemeral] has none.
 */
internal object WpeProfiles {
    /** What an app may set to name itself: [DesktopApp.APP_PROPERTY]. */
    const val APP_PROPERTY: String = DesktopApp.APP_PROPERTY

    /** The file in a profile's folder whose lock says which process has it. */
    const val LOCK_FILE: String = ".lock"

    /** This process's app's folder name. */
    val app: String get() = DesktopApp.id

    /** [DesktopApp.appId]. */
    fun appId(property: String?, launcher: String?, command: String?, classPath: String?): String =
        DesktopApp.appId(property, launcher, command, classPath)

    /** [profile]'s folder in the app's [root]; null for an ephemeral one, which keeps nothing. */
    fun dataDir(root: File, profile: WebProfile): File? = when (profile) {
        WebProfile.Default -> File(root, "default")
        is WebProfile.Named -> File(root, "profiles/${profile.id}")
        WebProfile.Ephemeral -> null
    }

    /** The folders this process has: locked from the first engine of their profile until it exits. */
    class Locks {
        private val held = HashMap<String, FileLock>()

        /** Locks [dir] for this process; null once it has it, else why it can't (another process of the app has it). */
        @Synchronized
        fun lock(dir: File): String? {
            val key = dir.absoluteFile.normalize().path
            if (key in held) return null
            val channel = try {
                dir.mkdirs()
                FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                return "can't lock $dir: ${e.message}"
            }
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                channel.close()
                return "can't lock $dir: ${e.message}"
            }
            if (lock == null) {
                channel.close()
                return "$dir is in use by another process of this app: WebKit can't share a profile's store between two"
            }
            held[key] = lock
            return null
        }
    }

    /** This process's. */
    val locks: Locks = Locks()
}
