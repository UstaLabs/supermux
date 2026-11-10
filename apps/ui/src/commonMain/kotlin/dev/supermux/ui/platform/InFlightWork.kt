package dev.supermux.ui.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's own work that closing it would cut (#113): a voice dictation or recording, an attachment upload. The
 * agents carry on through an update's restart; these live in the app, so "Restart to update" waits for them.
 */
object InFlightWork {
    enum class Kind { Recording, Upload }

    private val _active = MutableStateFlow<List<Kind>>(emptyList())

    /** What is running now, one entry per piece of work. */
    val active: StateFlow<List<Kind>> = _active.asStateFlow()

    /** Work of [kind] began; call the returned function once it ends (a second call does nothing). */
    fun begin(kind: Kind): () -> Unit {
        _active.update { it + kind }
        var ended = false
        return {
            if (!ended) {
                ended = true
                _active.update { list -> list.toMutableList().also { it.remove(kind) } }
            }
        }
    }

    internal fun clearForTest() {
        _active.value = emptyList()
    }
}
