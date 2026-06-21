package dev.supermux.android.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.supermux.auth.SecureTokenStore
import dev.supermux.net.BrokerApi
import dev.supermux.net.PairUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the onboarding/pairing flow. */
sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Validating : PairingUiState
    /** Validated against the broker; awaiting the trust-on-first-connect confirmation. */
    data class Confirm(val pair: PairUrl, val deviceName: String) : PairingUiState
    data class Error(val message: String) : PairingUiState
    /** Persisted; the gate can flip to the live app. */
    data object Paired : PairingUiState
}

/**
 * Owns the first-connect pairing flow: parse an input (URL / deep link / manual
 * host+token) → validate it against the broker via the native `/pair.json` (with a
 * `/me` fallback for the resolved device name) → on the user's TOFU confirm, persist
 * BOTH the base URL and token into the encrypted [SecureTokenStore].
 *
 * Mirrors iOS behavior (same endpoints, same URL parsing, same secure-store contract)
 * in a native-M3 presentation. Validation uses a throwaway [BrokerApi] built with the
 * candidate base+token; nothing is persisted until [confirmPersist].
 */
class PairingViewModel : ViewModel() {
    private val store = SecureTokenStore()
    private val http = HttpClient(CIO)

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** Last-known broker base URL (e.g. from a prior partial pairing) — the deep-link / bare-token fallback. */
    fun fallbackBaseUrl(): String? = store.loadBaseUrl()

    fun resetError() {
        if (_state.value is PairingUiState.Error) _state.value = PairingUiState.Idle
    }

    /**
     * Parse [input] and validate it against the broker. On success transitions to
     * [PairingUiState.Confirm] (does NOT persist — that waits for the TOFU confirm).
     * On any failure transitions to [PairingUiState.Error].
     */
    fun validate(input: String, fallbackBase: String? = fallbackBaseUrl()) {
        val parsed = PairUrl.parse(input, fallbackBase)
        if (parsed == null) {
            _state.value = PairingUiState.Error(
                "Couldn't read a token from that — paste the full pairing link (it contains ?t=…).",
            )
            return
        }
        validatePair(parsed)
    }

    /** Validate an already-parsed [PairUrl] (e.g. from a `supermux://pair` deep link). */
    fun validatePair(parsed: PairUrl) {
        _state.value = PairingUiState.Validating
        viewModelScope.launch {
            val name = probeDeviceName(parsed)
            _state.value = if (name != null) {
                PairingUiState.Confirm(parsed, name)
            } else {
                PairingUiState.Error(
                    "That broker rejected the token. Check the host is reachable and the link is current.",
                )
            }
        }
    }

    /**
     * Validate against the broker and return the resolved device name, or null when the
     * token/host is bad. Tries `/pair.json` first (the purpose-built native shim), then
     * `/me`. [BrokerApi.decode] surfaces non-2xx/transport failures as CancellationException,
     * so a bad token simply yields null here (caught below) rather than crashing.
     */
    private suspend fun probeDeviceName(p: PairUrl): String? {
        val candidate = BrokerApi(p.baseUrl, p.token, http)
        runCatching { candidate.pairJson(p.token) }.getOrNull()
            ?.takeIf { it.token.isNotEmpty() }
            ?.let { return it.name.ifBlank { "this broker" } }
        return runCatching { candidate.me() }.getOrNull()
            ?.takeIf { it.paired }
            ?.let { it.device?.ifBlank { "this broker" } ?: "this broker" }
    }

    /** TOFU confirm: persist host+token atomically, then mark Paired. */
    fun confirmPersist(p: PairUrl) {
        store.saveBaseUrl(p.baseUrl)
        store.save(p.token)
        _state.value = PairingUiState.Paired
    }

    /** Dismiss the TOFU dialog without persisting — back to entry. */
    fun cancelConfirm() {
        _state.value = PairingUiState.Idle
    }

    override fun onCleared() {
        http.close()
        super.onCleared()
    }
}
