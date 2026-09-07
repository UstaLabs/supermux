package dev.supermux.ui.intro

import dev.supermux.net.PairUrl
import dev.supermux.pairing.InMemoryPairingTokenStore
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.PairingTokenStore
import dev.supermux.state.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [PairingState] whose broker probe is a lambda, on an unconfined scope so a `validate` has
 * already landed by the time the composition is idle. The httpFactory is never reached — an
 * injected probe bypasses it — so a shared-screen test never opens a socket.
 */
internal fun testPairing(
    store: PairingTokenStore = InMemoryPairingTokenStore(),
    probe: suspend (PairUrl) -> String? = { "my-laptop" },
) = PairingState(
    store = store,
    scope = CoroutineScope(Dispatchers.Unconfined),
    probeOverride = probe,
)

/** A [SettingsStore] that also counts writes, for "written exactly once" assertions. */
internal class CountingSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    val writes = mutableListOf<Pair<String, String?>>()
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        writes.add(key to value)
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}
