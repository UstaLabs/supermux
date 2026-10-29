package dev.supermux.webview.engine.android

import android.util.Log
import androidx.webkit.Profile
import androidx.webkit.ProfileStore

/**
 * The WebView profiles of [dev.supermux.webview.WebProfile.Ephemeral] engines: one each, so wiping one engine's data
 * never touches another's (the contract's **After reset**). WebView can't delete a profile loaded in this process
 * (`ProfileStore.deleteProfile`), so names are reused instead, wiped: a released engine's profile is wiped before the
 * next engine gets it. Profiles an earlier process left are deleted the first time one is needed. Main thread only.
 */
internal object EphemeralProfiles {
    /** A profile for a new engine; [reused] if an engine had it before, so its HTTP cache is cleared on the new WebView. */
    class Taken(val name: String, val reused: Boolean)

    private val free = ArrayDeque<String>()
    private var next = 0
    private var cleaned = false

    fun take(): Taken {
        if (!cleaned) {
            cleaned = true
            deleteLeftovers()
        }
        val name = free.removeFirstOrNull() ?: return Taken(ephemeralProfile(next++), reused = false)
        return Taken(name, reused = true)
    }

    /** [name]'s engine is gone: the profile is wiped, then free for the next one. */
    fun giveBack(name: String) {
        wipe(ProfileStore.getInstance().getOrCreateProfile(name)) { free.addLast(name) }
    }

    /** Wipes [profile]'s cookies, web storage and geolocation grants, then calls [done] on main. */
    fun wipe(profile: Profile, done: () -> Unit) {
        profile.webStorage.deleteAllData()
        profile.geolocationPermissions.clearAll()
        profile.cookieManager.removeAllCookies {
            profile.cookieManager.flush()
            done()
        }
    }

    private fun deleteLeftovers() {
        val store = ProfileStore.getInstance()
        for (name in store.allProfileNames) {
            if (!name.startsWith(EPHEMERAL_PROFILE_PREFIX)) continue
            try {
                store.deleteProfile(name)
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not delete the leftover profile $name", e)
            }
        }
    }
}
