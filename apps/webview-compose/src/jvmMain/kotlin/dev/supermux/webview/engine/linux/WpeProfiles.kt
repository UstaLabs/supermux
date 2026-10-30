package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebProfile
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Where WebKit keeps an app's profiles on Linux, and who may: each app has folders of its own, and one process of it
 * at a time has a profile's folder, as WebKit's network process isn't made to share its store with another's.
 * - `$XDG_DATA_HOME/dev.supermux.webview/wpe/<app>/default` (and `$XDG_CACHE_HOME/…/<app>`): the [WebProfile.Default].
 * - `…/<app>/profiles/<id>`: a [WebProfile.Named].
 * - [WebProfile.Ephemeral] has none.
 */
internal object WpeProfiles {
    /** What an app may set to name itself (`-Ddev.supermux.webview.app=<name>`), where its own name won't do. */
    const val APP_PROPERTY: String = "dev.supermux.webview.app"

    /** The file in a profile's folder whose lock says which process has it. */
    const val LOCK_FILE: String = ".lock"

    /** This process's app's folder name ([appId] of its system properties). */
    val app: String by lazy {
        appId(System.getProperty(APP_PROPERTY), System.getProperty("jpackage.app-path"), System.getProperty("sun.java.command"), System.getProperty("java.class.path"))
    }

    /**
     * The app's folder name, without the app doing anything:
     * - [property], if the app names itself;
     * - else its launcher's name ([launcher], `jpackage.app-path`: a Compose Desktop package's);
     * - else its main class or jar ([command], `sun.java.command`), with a hash of where its own code lives ([classPath]'s
     *   first entry): two apps whose main is the root package's `MainKt` don't share a store.
     * Only `A-Z a-z 0-9 . _ -`: a folder's name, never a path.
     */
    fun appId(property: String?, launcher: String?, command: String?, classPath: String?): String {
        property?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        launcher?.takeIf { it.isNotBlank() }?.let { return sanitize(File(it).name) }
        val main = command?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return "default"
        val name = if (main.endsWith(".jar")) File(main).name.removeSuffix(".jar") else main
        val origin = classPath?.substringBefore(File.pathSeparatorChar)?.takeIf { it.isNotEmpty() } ?: main
        return sanitize(name) + "-" + sha256(origin).take(8)
    }

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

    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() && it.code < 0x80 || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("").take(64)
            .trimStart('.').ifEmpty { "default" }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
