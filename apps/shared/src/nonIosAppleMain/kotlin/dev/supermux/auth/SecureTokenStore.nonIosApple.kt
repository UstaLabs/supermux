package dev.supermux.auth

/**
 * The in-memory [SecureTokenStore] for the Apple targets that are NOT the iPhone app —
 * watchOS and macOS.
 *
 * It is not a placeholder for either of them:
 *
 *  - **watchOS** never holds a pairing of its own. The phone provisions the watch over
 *    WatchConnectivity (`PhoneWatchProvisioner`), so there is no Keychain item to read and
 *    nothing to persist between launches beyond what the phone re-sends.
 *  - **macOS** deliberately keeps its token in `~/.mux/state/native-client-token`, not the login
 *    keychain: a reinstalled local build changes its designated requirement, and the read would
 *    then block on an authorization prompt before the first window appears. Swift's `KeychainStore`
 *    already implements that file path for the Mac; this actual is simply unused there.
 *
 * The iOS actual (cluster H2) is a real Keychain store and lives in `iosMain`, which is why this
 * one sits in the `nonIosAppleMain` intermediate rather than in `appleMain`.
 */
actual class SecureTokenStore actual constructor() {
    private var token: String? = null
    private var baseUrl: String? = null
    actual fun save(token: String) { this.token = token }
    actual fun load(): String? = token
    actual fun clear() { token = null; baseUrl = null }
    actual fun saveBaseUrl(url: String) { this.baseUrl = url }
    actual fun loadBaseUrl(): String? = baseUrl
}
