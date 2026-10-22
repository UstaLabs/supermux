package dev.supermux.media

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Whole files kept in memory, least recently used out first once [maxBytes] is passed. Safe to read
 * from the UI thread while a download fills it from a worker. An entry over [maxEntryBytes] is never
 * kept, so one long video cannot flush every image.
 */
class MemoryLru(private val maxBytes: Long, private val maxEntryBytes: Long) {
    private val lock = SynchronizedObject()

    // Insertion order is recency order: a hit is removed and re-inserted at the end.
    private val entries = LinkedHashMap<String, ByteArray>()
    private var total = 0L

    fun get(key: String): ByteArray? = synchronized(lock) {
        val bytes = entries.remove(key) ?: return@synchronized null
        entries[key] = bytes
        bytes
    }

    fun put(key: String, bytes: ByteArray) {
        if (bytes.size > maxEntryBytes) return
        synchronized(lock) {
            entries.remove(key)?.let { total -= it.size }
            entries[key] = bytes
            total += bytes.size
            val it = entries.entries.iterator()
            while (total > maxBytes && it.hasNext()) {
                total -= it.next().value.size
                it.remove()
            }
        }
    }

    fun remove(key: String) {
        synchronized(lock) { entries.remove(key)?.let { total -= it.size } }
    }

    /** Drop every entry whose key starts with [prefix] — one host's files. */
    fun removePrefix(prefix: String) {
        synchronized(lock) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key.startsWith(prefix)) {
                    total -= e.value.size
                    it.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            total = 0
        }
    }

    val sizeBytes: Long get() = synchronized(lock) { total }
}
