package dev.supermux.android.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.supermux.auth.SecureTokenStore
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.asPairingStore
import dev.supermux.state.cioHttpFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Android's retained home for the shared [PairingState] (cluster G6).
 *
 * The state machine itself is `:shared` and platform-free; what Android needs on top is the ONE
 * thing the deleted `PairingViewModel` gave it and a `remember` cannot: survival across a
 * configuration change. `MainActivity` declares no `configChanges`, so a rotation while the
 * broker probe is in flight would otherwise destroy the machine mid-`Validating` and take an
 * open TOFU dialog with it — the user rotates the phone and their pairing silently restarts.
 *
 * So the holder is a `ViewModel`: it owns the state for the activity's whole lifetime, runs it on
 * [viewModelScope] (exactly the scope the old ViewModel used), and releases the throwaway probe
 * client in [onCleared] — the counterpart of `PairingViewModel.onCleared`.
 *
 * @param build the seam that makes the state, so a unit test can hand it an in-memory store and a
 *   mock engine. Production takes the default: the encrypted [SecureTokenStore] onboarding has
 *   always written, and the app's own CIO factory.
 */
class PairingHolder(
    build: (CoroutineScope) -> PairingState = { scope ->
        PairingState(
            store = SecureTokenStore().asPairingStore(),
            scope = scope,
            httpFactory = { cioHttpFactory()(null) },
        )
    },
) : ViewModel() {
    val pairing: PairingState = build(viewModelScope)

    override fun onCleared() {
        pairing.close()
        super.onCleared()
    }

    /**
     * What the framework does when the activity finishes for good. `ViewModel.clear()` is
     * internal to lifecycle, so a unit test cannot drive the real teardown — this is the way in,
     * and it is the same call the framework makes.
     */
    internal fun clearForTest() = onCleared()
}
