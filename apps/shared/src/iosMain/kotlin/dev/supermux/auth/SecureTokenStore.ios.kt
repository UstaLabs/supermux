package dev.supermux.auth

// TODO(Spec 2): replace with the iOS Keychain (SecItemAdd/SecItemCopyMatching/
// SecItemDelete). In-memory for now so iosMain compiles (on a Mac); this must
// never ship as the iOS auth store.
actual class SecureTokenStore actual constructor() {
    private var token: String? = null
    actual fun save(token: String) { this.token = token }
    actual fun load(): String? = token
    actual fun clear() { token = null }
}
