package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebProfile
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Where WebView2 keeps an app's web data, and under which profile names (D8). Each app has a user data folder of its
 * own; inside it, WebView2's `Default` profile, a profile per `Named` id, and InPrivate profiles in numbered slots for
 * `Ephemeral` engines, which a lock file keeps to one engine of one process at a time.
 */
internal object Wv2Profiles {
    const val EPHEMERAL_PREFIX: String = "wvc-eph-"
    const val MAX_SLOTS: Int = 64

    fun userDataFolder(localAppData: File?, app: String): File? = localAppData?.let { File(it, "dev.supermux.webview/webview2/$app") }

    /** WebView2's name for [profile]: "" for its own default; null for an ephemeral one, which takes a slot. */
    fun profileName(profile: WebProfile): String? = when (profile) {
        WebProfile.Default -> ""
        is WebProfile.Named -> "wvc-n-" + sanitize(profile.id).take(32) + "-" + sha256(profile.id).take(8)
        WebProfile.Ephemeral -> null
    }

    fun ephemeralName(slot: Int): String = "$EPHEMERAL_PREFIX$slot"

    /** WebView2's profile names: ASCII letters, digits, `-` and `_` here, lowercase (WebView2 ignores case). */
    private fun sanitize(id: String): String =
        id.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') it else '_' }.joinToString("").ifEmpty { "_" }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** This process's ephemeral slots in [folder]: claimed by locking `ephemeral/slot-<n>.lock`, the lowest free first. */
    class Slots(private val folder: File) {
        private val held = HashMap<Int, FileLock>()

        @Synchronized
        fun claim(): Int? {
            val dir = File(folder, "ephemeral").apply { mkdirs() }
            for (n in 1..MAX_SLOTS) {
                if (n in held) continue
                val channel = try {
                    FileChannel.open(File(dir, "slot-$n.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                } catch (e: IOException) {
                    continue
                }
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    null
                } catch (e: IOException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    continue
                }
                held[n] = lock
                return n
            }
            return null
        }

        @Synchronized
        fun release(slot: Int) {
            held.remove(slot)?.let { lock ->
                runCatching { lock.release() }
                runCatching { lock.channel().close() }
            }
        }
    }

    private val slots = HashMap<String, Slots>()

    /** The process's slots for [folder]. */
    @Synchronized
    fun slots(folder: File): Slots = slots.getOrPut(folder.absolutePath) { Slots(folder) }
}
