package dev.supermux.webview.runtime

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.snapshots.Snapshot

/**
 * The Compose overlays and scrims drawn over web views right now, in window pixels (spec §6).
 * `Modifier.webViewOverlay` and `Modifier.webViewScrim` keep them current. A live page that sits above Compose cuts
 * [shapes] out of itself and dims under [scrims] ([computeCutouts]).
 * - Both are snapshot state, so a reader (a `snapshotFlow`, a composition) hears of every change.
 * - A read is one state read. Each change publishes new lists that never change, so readers copy and lock nothing,
 *   and may keep what they read.
 * - Writing what is already there changes no state: a layout pass that moves nothing wakes no reader.
 * - Entries are kept by key, in the order their keys registered. Changing an entry keeps its place.
 *
 * Main thread only.
 */
@Stable
class OverlayRegistry {
    private val overlayEntries = mutableStateOf(Entries<OverlayShape>(), referentialEqualityPolicy())
    private val scrimEntries = mutableStateOf(Entries<ScrimShape>(), referentialEqualityPolicy())

    /** The overlays, in the order they registered. */
    val shapes: List<OverlayShape> get() = overlayEntries.value.values

    /** The scrims, in the order they registered: the order stacked modals open in, so they compound as drawn. */
    val scrims: List<ScrimShape> get() = scrimEntries.value.values

    /** Registers [shape] for [key], in place of what [key] had; null forgets [key]. Keys are compared with `==`. */
    fun setOverlay(key: Any, shape: OverlayShape?) = update(overlayEntries, key, shape)

    /** Registers [scrim] for [key], in place of what [key] had; null forgets [key]. Keys are compared with `==`. */
    fun setScrim(key: Any, scrim: ScrimShape?) = update(scrimEntries, key, scrim)

    private fun <T : Any> update(state: MutableState<Entries<T>>, key: Any, value: T?) {
        // A writer doesn't come to depend on what it writes, wherever it runs.
        val current = Snapshot.withoutReadObservation { state.value }
        val next = current.with(key, value)
        if (next !== current) state.value = next
    }
}

/** Values by key, in the order their keys came. It never changes: a change makes new [Entries], with new [values]. */
private class Entries<T : Any>(private val keys: List<Any> = emptyList(), val values: List<T> = emptyList()) {
    /** With [value] for [key], or without [key] when [value] is null; this one when it already is that. */
    fun with(key: Any, value: T?): Entries<T> {
        val index = keys.indexOf(key)
        return when {
            value == null -> if (index < 0) this else Entries(keys.without(index), values.without(index))
            index < 0 -> Entries(keys + key, values + value)
            values[index] == value -> this
            else -> Entries(keys, values.toMutableList().apply { set(index, value) })
        }
    }

    private fun <E> List<E>.without(index: Int): List<E> = toMutableList().apply { removeAt(index) }
}
