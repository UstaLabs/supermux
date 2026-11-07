package dev.supermux.webview.engine.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

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
    private val onMain = Executor { Handler(Looper.getMainLooper()).post(it) }

    /**
     * Whether this WebView can wipe a profile whole ([wipe]). Without it, an Ephemeral engine's reset fails, so the pool
     * destroys the engine, and a released engine's profile is never handed on: an earlier process's are deleted.
     */
    val canWipe: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    fun take(): Taken {
        if (!cleaned) {
            cleaned = true
            deleteLeftovers()
        }
        val name = free.removeFirstOrNull() ?: return Taken(ephemeralProfile(next++), reused = false)
        return Taken(name, reused = true)
    }

    /** [name]'s engine is gone: the profile is wiped, then free for the next one; never handed on unwiped. */
    fun giveBack(name: String) {
        if (!canWipe) return
        wipe(ProfileStore.getInstance().getOrCreateProfile(name)) { free.addLast(name) }
    }

    /**
     * Wipes [profile] whole, then calls [done] on main: all its browsing data, as WebView deletes it
     * (`WebStorageCompat.deleteBrowsingData`: cookies, web storage of every kind, Cache Storage, service workers and
     * caches), then its cookies once more, flushed, and its geolocation grants. Only where [canWipe].
     * `WebStorage.deleteAllData` alone left Cache Storage and service workers, and returned before it was done.
     */
    fun wipe(profile: Profile, done: () -> Unit) {
        check(canWipe) { "this WebView can't wipe a profile whole" }
        profile.geolocationPermissions.clearAll()
        WebStorageCompat.deleteBrowsingData(profile.webStorage, onMain) {
            profile.cookieManager.removeAllCookies {
                profile.cookieManager.flush()
                done()
            }
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
