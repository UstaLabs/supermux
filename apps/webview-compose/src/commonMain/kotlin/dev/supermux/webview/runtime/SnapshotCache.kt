package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import dev.supermux.webview.WebProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A snapshot's identity: the content and the profile it was shown in (a [PageKey]'s), its pixel size, the theme it was
 * taken in and the density it was drawn at. [pixelsPerDp]: how many of its pixels make a dp of the view it was taken
 * of, fewer than [density] when it was kept smaller (three pixels a dp at most, the pixel cap, an engine's own draw
 * stepped down), so its cover is drawn at its true size.
 */
public data class SnapshotKey(
    val contentKey: String,
    val profile: WebProfile,
    val width: Int,
    val height: Int,
    val dark: Boolean,
    val density: Float,
    val pixelsPerDp: Float = density,
)

/**
 * The snapshots of pages, as covers for their views (cache v3, sharp snapshots). Main thread only: the thread of the
 * runtime's scope.
 * - **Stored** snapshots are encoded ([SnapshotCodec]: JPEG, a tenth to a twentieth of their pixels' bytes), so many
 *   hundreds of sharp pages fit. Least recently used first out, within [maxBytes] of stored bytes, and less while
 *   memory is short ([WebViewRuntime.onMemoryPressure]).
 * - **Decoded** ones, ready to draw, are kept beside them, least recently used first out, within [decodedMaxBytes] of
 *   pixel bytes: a snapshot put with its image, and every decode, until newer ones push them out. Only stored
 *   snapshots are kept decoded.
 * - [request] gives a decoded snapshot at once, or starts its decode, off the main thread, once however many views
 *   ask, and tells each of them when it is done.
 */
public class SnapshotCache internal constructor(
    /** The budget for stored snapshots, in encoded bytes. */
    public val maxBytes: Long,
    /** The budget for decoded snapshots, in pixel bytes. */
    public val decodedMaxBytes: Long,
    /** Where decodes run: their results come back on its thread. */
    private val scope: CoroutineScope,
    /** How snapshots are encoded and decoded. Tests code them on the UI thread instead. */
    internal var codec: ImageCodec = SnapshotCodec,
) {
    /** Stored bytes, and what they cost when they came in. */
    private class Entry(val bytes: ByteArray) {
        val cost: Long = bytes.size.toLong()
    }

    /** An image and what it cost when it came in, so it leaves with exactly that, whatever it reports later. */
    private class Decoded(val image: ImageBitmap, val cost: Long)

    /** A decode under way, and the views waiting for it: their callbacks, and how many asked without one (prefetches). */
    private class Decode {
        val waiters = LinkedHashSet<(SnapshotKey, ImageBitmap) -> Unit>()
        var prefetches = 0
        var job: Job? = null
        var done = false
        var image: ImageBitmap? = null
    }

    private val entries = LinkedHashMap<SnapshotKey, Entry>()
    private val ready = LinkedHashMap<SnapshotKey, Decoded>()
    private val decodes = HashMap<SnapshotKey, Decode>()

    /** The bytes of the snapshots stored. */
    public var sizeBytes: Long = 0
        private set

    /** How many snapshots are stored. */
    public val count: Int get() = entries.size

    /** The pixel bytes of the snapshots kept decoded. */
    public var decodedBytes: Long = 0
        private set

    /** How many snapshots are kept decoded. */
    public val decodedCount: Int get() = ready.size

    /**
     * A lower budget for stored snapshots while memory is short, or null. Setting it evicts down to it at once, and the
     * cache keeps within it, as within [maxBytes], until it is null again. It never raises the budget above [maxBytes].
     */
    internal var limitBytes: Long? = null
        set(value) {
            field = value
            trimTo(budget)
        }

    /** The same for decoded snapshots, against [decodedMaxBytes]. */
    private var decodedLimitBytes: Long? = null
        set(value) {
            field = value
            trimDecodedTo(decodedBudget)
        }

    /** What the cache may store now. */
    private val budget: Long get() = minOf(maxBytes, limitBytes ?: maxBytes)

    /** What the cache may keep decoded now. */
    private val decodedBudget: Long get() = minOf(decodedMaxBytes, decodedLimitBytes ?: decodedMaxBytes)

    /** The snapshot stored under [key], as stored; it counts as used. */
    internal operator fun get(key: SnapshotKey): ByteArray? = use(key)?.bytes

    /** Whether a snapshot is stored under [key]. */
    public operator fun contains(key: SnapshotKey): Boolean = key in entries

    /** Marks the snapshot stored under [key] used, as a view showing it does. False if there is none. */
    internal fun touch(key: SnapshotKey): Boolean = use(key) != null

    /**
     * Stores [bytes], [codec]-encoded, under [key] in place of what was there, and keeps [decoded], the image they
     * encode, ready to draw. Views waiting for the old one's decode get [decoded], or wait for these bytes' decode. True
     * if it stored them. A snapshot bigger than the whole cache, or than what memory pressure leaves of it, isn't kept,
     * and what is stored, under [key] too, stays.
     */
    public fun put(key: SnapshotKey, bytes: ByteArray, decoded: ImageBitmap? = null): Boolean {
        val entry = Entry(bytes)
        if (entry.cost > budget) return false
        val waiting = decodes.remove(key)?.let { superseded ->
            superseded.job?.cancel()
            superseded.waiters
        }
        remove(key)
        entries[key] = entry
        sizeBytes += entry.cost
        trimTo(budget)
        if (decoded != null) keepDecoded(key, decoded)
        if (waiting.isNullOrEmpty()) return true
        if (decoded != null) {
            waiting.forEach { it(key, decoded) }
        } else {
            // A codec that doesn't suspend gives the image back at once, instead of to the waiter.
            waiting.forEach { waiter -> request(key, waiter)?.let { waiter(key, it) } }
        }
        return true
    }

    /**
     * The snapshot stored under [key], decoded, if it is ready now. If not, its decode starts, off the main thread
     * (one for every view that asks), and [onDecoded], if given, gets the image once it is done, on the main thread,
     * whether or not the cache keeps it; nothing at all if the snapshot goes meanwhile or doesn't decode. Without
     * [onDecoded] it is a prefetch: it starts a decode only if the cache would keep what it gives. Either way the
     * snapshot counts as used.
     */
    public fun request(key: SnapshotKey, onDecoded: ((SnapshotKey, ImageBitmap) -> Unit)? = null): ImageBitmap? {
        val entry = use(key) ?: return null
        ready.remove(key)?.let { kept ->
            ready[key] = kept
            return kept.image
        }
        val running = decodes[key]
        if (running != null) {
            if (onDecoded != null) running.waiters += onDecoded else running.prefetches++
            return null
        }
        if (onDecoded == null && decodedBudget <= 0) return null
        val decode = Decode()
        decodes[key] = decode
        // Undispatched: a codec that doesn't suspend has decoded by the time launch returns.
        decode.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                finish(key, decode, codec.decode(entry.bytes))
            } finally {
                // Cancelled with the scope: the next request starts another.
                if (!decode.done && decodes[key] === decode) decodes.remove(key)
            }
        }
        if (decode.done) return decode.image
        if (onDecoded != null) decode.waiters += onDecoded else decode.prefetches++
        return null
    }

    /**
     * A view no longer wants [key] decoded: it left. Its [onDecoded] goes, or, if null, a prefetch it asked for. A decode
     * nobody waits for any more is given up and keeps nothing, so it pushes no cover about to be seen out of the decoded
     * cache.
     */
    internal fun withdraw(key: SnapshotKey, onDecoded: ((SnapshotKey, ImageBitmap) -> Unit)?) {
        val decode = decodes[key] ?: return
        if (onDecoded != null) decode.waiters -= onDecoded else if (decode.prefetches > 0) decode.prefetches--
        if (decode.waiters.isNotEmpty() || decode.prefetches > 0) return
        decodes.remove(key)
        decode.job?.cancel()
    }

    private fun finish(key: SnapshotKey, decode: Decode, image: ImageBitmap?) {
        decode.done = true
        decode.image = image
        // Superseded: the snapshot was replaced or removed meanwhile, and its waiters were seen to.
        if (decodes[key] !== decode) return
        decodes.remove(key)
        if (image == null) {
            // Bytes that don't decode are of no use to anyone.
            remove(key)
            return
        }
        keepDecoded(key, image)
        decode.waiters.forEach { it(key, image) }
    }

    /** Whether the snapshot stored under [key] is decoded, ready to draw. It doesn't count as used. */
    internal fun isDecoded(key: SnapshotKey): Boolean = key in ready

    /** Whether the snapshot stored under [key] is being decoded. */
    internal fun isDecoding(key: SnapshotKey): Boolean = key in decodes

    /** Memory is short: every decoded snapshot goes, and none is kept until it ends; stored ones give back half, once. */
    internal fun onMemoryPressure() {
        // The decoded ones first: each is many times its stored bytes, and decodes again from them.
        decodedLimitBytes = 0
        // Half of what it stores: half of its budget would free nothing from a cache that is less than half full, and
        // would let it grow back past what it held. Once a window: halving again at every warning would end at nothing.
        if (limitBytes == null) limitBytes = sizeBytes / 2
    }

    /** Memory is fine again: both budgets are whole again. */
    internal fun onPressureEnded() {
        decodedLimitBytes = null
        limitBytes = null
    }

    /** Removes the snapshot stored under [key], decoded too; a decode of it under way gives nothing. */
    public fun remove(key: SnapshotKey) {
        entries.remove(key)?.let { sizeBytes -= it.cost }
        dropDecoded(key)
        decodes.remove(key)?.job?.cancel()
    }

    /** Removes every snapshot of the content of [contentKey], in every profile, size, theme and density. */
    public fun forget(contentKey: String) {
        entries.keys.filter { it.contentKey == contentKey }.forEach(::remove)
    }

    /** Removes every snapshot. */
    public fun clear() {
        entries.clear()
        sizeBytes = 0
        ready.clear()
        decodedBytes = 0
        decodes.values.forEach { it.job?.cancel() }
        decodes.clear()
    }

    /** Evicts least-recently-used stored snapshots, and their decoded copies, until at most [limit] bytes remain. */
    public fun trimTo(limit: Long) {
        while (sizeBytes > limit) remove(entries.keys.firstOrNull() ?: return)
    }

    /** Evicts least-recently-used decoded snapshots until they hold at most [limit] pixel bytes; they stay stored. */
    internal fun trimDecodedTo(limit: Long) {
        val iterator = ready.values.iterator()
        while (decodedBytes > limit && iterator.hasNext()) {
            decodedBytes -= iterator.next().cost
            iterator.remove()
        }
    }

    /** The entry stored under [key], made the most recently used. */
    private fun use(key: SnapshotKey): Entry? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry
    }

    private fun keepDecoded(key: SnapshotKey, image: ImageBitmap) {
        dropDecoded(key)
        val cost = pixelBytes(image)
        if (cost > decodedBudget) return
        ready[key] = Decoded(image, cost)
        decodedBytes += cost
        trimDecodedTo(decodedBudget)
    }

    private fun dropDecoded(key: SnapshotKey) {
        ready.remove(key)?.let { decodedBytes -= it.cost }
    }
}

/** The bytes [image]'s pixels take, by their format. */
internal fun pixelBytes(image: ImageBitmap): Long = image.width.toLong() * image.height * bytesPerPixel(image.config)

private fun bytesPerPixel(config: ImageBitmapConfig): Int = when (config) {
    ImageBitmapConfig.Alpha8 -> 1
    ImageBitmapConfig.Rgb565 -> 2
    ImageBitmapConfig.F16 -> 8
    else -> 4 // Argb8888, Gpu, and any format added later
}
