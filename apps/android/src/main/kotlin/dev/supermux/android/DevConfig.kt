package dev.supermux.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.supermux.auth.SecureTokenStore

object DevConfig {
    const val HOME = "/home/user"

    private const val PHYSICAL_BROKER = "ws://CHANGE_ME:9898"  // set to your broker host
    private const val EMULATOR_BROKER = "ws://10.0.2.2:9898"

    /** Debug fallback when [SecureTokenStore] is empty (debug builds only). */
    const val DEBUG_TOKEN = ""  // debug-only fallback; set a dev token or leave empty

    fun brokerUrl(): String = if (isEmulator()) EMULATOR_BROKER else PHYSICAL_BROKER

    fun resolveToken(context: Context): String {
        val store = SecureTokenStore()
        store.load()?.let { return it }
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && DEBUG_TOKEN.isNotEmpty()) {
            store.save(DEBUG_TOKEN)
            return DEBUG_TOKEN
        }
        return ""
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk_gphone")
    }
}
