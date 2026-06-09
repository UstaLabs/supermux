package dev.supermux.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// The app calls SecureTokenStoreContext.init(applicationContext) once in onCreate.
object SecureTokenStoreContext {
    @Volatile var appContext: Context? = null
    fun init(ctx: Context) { appContext = ctx.applicationContext }
}

actual class SecureTokenStore actual constructor() {
    private val prefs by lazy {
        val ctx = requireNotNull(SecureTokenStoreContext.appContext) {
            "SecureTokenStoreContext.init(context) must be called before use"
        }
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "supermux_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    actual fun save(token: String) { prefs.edit().putString("token", token).apply() }
    actual fun load(): String? = prefs.getString("token", null)
    actual fun clear() { prefs.edit().remove("token").apply() }
}
