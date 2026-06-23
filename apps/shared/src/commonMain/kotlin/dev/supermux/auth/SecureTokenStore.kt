package dev.supermux.auth

// Cross-platform secure token storage for the device (bearer) token.
// Platform actuals:
//   - iOS  : Keychain (stubbed here; real impl in Spec 2 on a Mac).
//   - JVM  : in-memory only — used by unit tests + a future desktop target;
//            NOT a shipping secure store.
//   - Android: Keystore / EncryptedSharedPreferences — added in the Android plan
//            when the android target is introduced.
expect class SecureTokenStore() {
    fun save(token: String)
    fun load(): String?
    fun clear()
    // Phase 2: the broker base URL persisted next to the token, so pairing can
    // store host+token atomically in one secure store. clear() drops both.
    fun saveBaseUrl(url: String)
    fun loadBaseUrl(): String?
}
