// The first-connect pairing state machine, shared by every host (cluster G6).
//
// It used to exist twice: Android's `PairingViewModel` (an androidx `ViewModel` over
// `SecureTokenStore`) and desktop's `PairingState` (a plain class over `DesktopTokenStore`,
// itself a port of the ViewModel). Same states, same endpoints, same URL parsing, same
// "nothing is persisted until the TOFU confirm" rule — so one class lives here and each host
// supplies its own credential store through [PairingTokenStore] and its own engine through
// `httpFactory` (the seam `HostStoreDeps` already uses for the same reason).
package dev.supermux.pairing

import dev.supermux.auth.SecureTokenStore
import dev.supermux.host.PairingPayload
import dev.supermux.net.BrokerApi
import dev.supermux.net.PairClaimResult
import dev.supermux.net.PairUrl
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the onboarding/pairing flow. */
sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Validating : PairingUiState
    /**
     * Awaiting the trust-on-first-connect confirmation. A link was validated against the broker and
     * [pair] carries its token. A [claim] has not been sent anywhere yet: [pair] is the address it will
     * be redeemed on, with no token, and [deviceName] is the name this device will register as.
     */
    data class Confirm(val pair: PairUrl, val deviceName: String, val claim: PairingPayload? = null) : PairingUiState
    data class Error(val message: String) : PairingUiState
    /** Persisted; the gate can flip to the live app. */
    data object Paired : PairingUiState
}

/**
 * The slice of a host's credential store [PairingState] needs: read the last-known base URL,
 * and write host+token on the TOFU confirm.
 *
 * Android's `SecureTokenStore` (Keystore-backed) and desktop's `DesktopTokenStore` (an
 * owner-only JSON file) already have exactly these four members; they just are not the same
 * type, so each host adapts its own — see [asPairingStore] and desktop's `DesktopTokenStore`
 * adapter in `Main.kt`.
 */
interface PairingTokenStore {
    fun load(): String?
    fun save(token: String)
    fun loadBaseUrl(): String?
    fun saveBaseUrl(url: String)
}

/** [SecureTokenStore] as a [PairingTokenStore] — the Android/iOS adapter. */
fun SecureTokenStore.asPairingStore(): PairingTokenStore = object : PairingTokenStore {
    override fun load(): String? = this@asPairingStore.load()
    override fun save(token: String) = this@asPairingStore.save(token)
    override fun loadBaseUrl(): String? = this@asPairingStore.loadBaseUrl()
    override fun saveBaseUrl(url: String) = this@asPairingStore.saveBaseUrl(url)
}

/** A process-local store — previews, tests and any caller with nothing to persist. */
class InMemoryPairingTokenStore : PairingTokenStore {
    private var token: String? = null
    private var baseUrl: String? = null
    override fun load(): String? = token?.takeIf { it.isNotBlank() }
    override fun save(token: String) { this.token = token }
    override fun loadBaseUrl(): String? = baseUrl?.takeIf { it.isNotBlank() }
    override fun saveBaseUrl(url: String) { baseUrl = url }
}

/**
 * Owns the first-connect pairing flow: read an input with [PairingCode] (the desktop host's claim
 * QR, a pairing link, a deep link, manual host+token) → validate a link against the broker via the
 * native `/pair.json` (with a `/me` fallback for the resolved device name) → on the user's TOFU
 * confirm, persist BOTH the base URL and token into [store]. A claim is redeemed on that confirm,
 * through the same [redeem] Add host uses, and the token it returns is what gets persisted.
 *
 * Validation uses a throwaway [BrokerApi] built with the candidate base+token; nothing is
 * persisted (and no claim redeemed) until [confirm].
 *
 * @param scope caller-owned (Android passed `viewModelScope`, desktop a composition scope).
 * @param httpFactory builds the throwaway probe client, LAZILY — a [PairingState] that never
 *   probes (every test with a [probeOverride]) never builds one, so [close] before any
 *   [validate] has nothing to release.
 * @param probeOverride injectable network seam (mirrors [dev.supermux.state.HostStore]'s
 *   `sendFrameOverride`/`apiOverride`) — tests inject a fake to assert state transitions without
 *   a live broker.
 * @param deviceName what this device registers as when it redeems a claim — the same platform name
 *   Add host offers.
 * @param claimOverride injectable `POST /pair/claim` (mirrors [dev.supermux.state.FleetStore]'s).
 */
class PairingState(
    private val store: PairingTokenStore,
    private val scope: CoroutineScope,
    httpFactory: () -> HttpClient = { error("PairingState: no httpFactory — pass one to probe a broker") },
    private val probeOverride: (suspend (PairUrl) -> String?)? = null,
    private val deviceName: String = "This device",
    private val claimOverride: (suspend (url: String, secret: String, deviceName: String) -> PairClaimResult?)? = null,
) {
    private var httpOrNull: HttpClient? = null
    private val buildHttp = httpFactory
    private fun http(): HttpClient = httpOrNull ?: buildHttp().also { httpOrNull = it }

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** Last-known broker base URL (e.g. from a prior partial pairing) — the deep-link / bare-token fallback. */
    fun fallbackBaseUrl(): String? = store.loadBaseUrl()

    fun resetError() {
        if (_state.value is PairingUiState.Error) _state.value = PairingUiState.Idle
    }

    /**
     * Read [input] and, for a link, validate it against the broker. On success transitions to
     * [PairingUiState.Confirm] (does NOT persist — that waits for the TOFU confirm). A claim goes
     * straight to Confirm: it is redeemed only once confirmed, so a dismissed dialog leaves the
     * one-time code usable. On any failure transitions to [PairingUiState.Error].
     */
    fun validate(input: String, fallbackBase: String? = fallbackBaseUrl()) {
        when (val code = PairingCode.parse(input, fallbackBase)) {
            is PairingCode.Token -> validatePair(code.pair)
            is PairingCode.Claim -> {
                val url = code.payload.claimUrl
                _state.value = if (url == null) {
                    PairingUiState.Error("That pairing link has no host URL.")
                } else {
                    PairingUiState.Confirm(PairUrl(PairUrl.normalize(url), token = ""), deviceName, code.payload)
                }
            }
            null -> _state.value = PairingUiState.Error(
                "Couldn't read a token from that — paste the full pairing link (it contains ?t=…).",
            )
        }
    }

    /** Validate an already-parsed [PairUrl] (e.g. from a `supermux://pair` deep link). */
    fun validatePair(parsed: PairUrl) {
        _state.value = PairingUiState.Validating
        scope.launch {
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
        probeOverride?.let { return it(p) }
        val candidate = BrokerApi(p.baseUrl, p.token, http())
        runCatching { candidate.pairJson(p.token) }.getOrNull()
            ?.takeIf { it.token.isNotEmpty() }
            ?.let { return it.name.ifBlank { "this broker" } }
        return runCatching { candidate.me() }.getOrNull()
            ?.takeIf { it.paired }
            ?.let { it.device?.ifBlank { "this broker" } ?: "this broker" }
    }

    /**
     * The TOFU confirm of the pending [PairingUiState.Confirm]: a link's token is persisted as it is;
     * a claim is redeemed first and the token it returns is persisted. A failed redemption (expired or
     * used claim, another host answering) is an [PairingUiState.Error] and persists nothing.
     */
    fun confirm() {
        val pending = _state.value as? PairingUiState.Confirm ?: return
        val claim = pending.claim ?: return confirmPersist(pending.pair)
        _state.value = PairingUiState.Validating
        scope.launch {
            when (val r = claim.redeem(pending.deviceName) { url, secret, name -> claimAt(url, secret, name) }) {
                is ClaimRedemption.Redeemed -> confirmPersist(PairUrl(PairUrl.normalize(r.url), r.result.deviceToken))
                is ClaimRedemption.Failed -> _state.value = PairingUiState.Error(r.message)
            }
        }
    }

    private suspend fun claimAt(url: String, secret: String, name: String): PairClaimResult? {
        claimOverride?.let { return it(url, secret, name) }
        return runCatching { BrokerApi(url, "", http()).pairClaim(secret, name) }.getOrNull()
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

    /**
     * Release the throwaway probe [HttpClient]. Counterpart of PairingViewModel.onCleared.
     *
     * Semantics (covered by PairingStateTest): idempotent (ktor's close() is safe to call
     * repeatedly, and an unbuilt client is nothing to close), safe before any [validate], and
     * does NOT reset the state machine or cancel the caller-owned [scope] — a [validate] after
     * close still parses and (via a probe seam) transitions normally; only a real network probe
     * would then fail, surfacing as the usual [PairingUiState.Error].
     */
    fun close() {
        httpOrNull?.close()
    }
}
