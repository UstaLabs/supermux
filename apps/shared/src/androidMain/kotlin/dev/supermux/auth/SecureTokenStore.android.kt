package dev.supermux.auth

import android.content.Context
import android.util.Log
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
        try {
            create(ctx)
        } catch (e: Exception) {
            // The keyset in supermux_secure can be undecryptable: written by the old
            // minified v1 build (broken Tink), or restored by backup onto a device
            // whose Keystore lacks the master key. Failing here crashed the app at
            // launch (the pairing gate reads the store in MainActivity.onCreate).
            // Reset to a re-pair instead of a crash loop.
            Log.w("SecureTokenStore", "encrypted prefs unreadable, resetting (re-pair required)", e)
            ctx.deleteSharedPreferences("supermux_secure")
            create(ctx)
        }
    }

    private fun create(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "supermux_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    actual fun save(token: String) { prefs.edit().putString("token", token).apply() }
    actual fun load(): String? = prefs.getString("token", null)
    actual fun clear() { prefs.edit().remove("token").remove("base_url").apply() }
    actual fun saveBaseUrl(url: String) { prefs.edit().putString("base_url", url).apply() }
    actual fun loadBaseUrl(): String? = prefs.getString("base_url", null)
}
