package dev.supermux.state

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * `SettingsStore` over `window.localStorage`, keys prefixed `supermux:`.
 *
 * [string] must be OBSERVED, not a one-shot read — the Appearance screen repaints from it (iOS
 * uses a callbackFlow for the same reason). localStorage has no same-tab change event, so every
 * [putString] bumps a revision that re-reads the key; the cross-tab `storage` event bumps it too.
 */
class LocalStorageSettingsStore(private val prefix: String = "supermux:") : SettingsStore {
    private val revision = MutableStateFlow(0)

    init {
        window.addEventListener("storage", { revision.value++ })
    }

    /** Synchronous read for the launch-time seeds (wasm has no `runBlocking`). */
    fun stringNow(key: String): String? = localStorage[prefix + key]

    override fun string(key: String): Flow<String?> =
        revision.map { stringNow(key) }.distinctUntilChanged()

    override suspend fun putString(key: String, value: String?) {
        if (value == null) localStorage.removeItem(prefix + key) else localStorage[prefix + key] = value
        revision.value++
    }
}
