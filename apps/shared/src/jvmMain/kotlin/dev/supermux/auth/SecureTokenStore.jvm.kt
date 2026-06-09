package dev.supermux.auth

// JVM actual: in-memory only. Exists so commonMain compiles + unit-tests run on
// this Linux host (and for a future desktop target). The shipping secure stores
// are iOS Keychain + Android Keystore — this is deliberately not persistent.
actual class SecureTokenStore actual constructor() {
    private var token: String? = null
    actual fun save(token: String) { this.token = token }
    actual fun load(): String? = token
    actual fun clear() { token = null }
}
