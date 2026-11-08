package dev.supermux.webview.engine.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.view.ContextThemeWrapper
import dev.supermux.webview.R

/**
 * The context an engine's WebView is built on: a [MutableContextWrapper] around a theme wrapper around the Activity that
 * shows it. Never the application context (spec §7): its density is the phone's even on a DeX or XREAL display.
 * - [moveTo] follows the Surface to another live Activity.
 * - [detach] lets a destroyed Activity go: the base becomes the application context until the engine is released.
 * - [dark] is what `prefers-color-scheme` says: for targetSdk 33+, WebView reads `android:isLightTheme` from this
 *   context's theme whenever it recomputes its preferences (chromium `DarkModeHelper.getLightTheme`).
 */
internal class EngineContext(activity: Activity, dark: Boolean) : MutableContextWrapper(themed(activity, dark)) {
    /** The Activity wrapped now; null once it was destroyed. */
    var activity: Activity? = activity
        private set

    var dark: Boolean = dark
        set(value) {
            if (field == value) return
            field = value
            rebase()
        }

    fun moveTo(activity: Activity) {
        this.activity = activity
        rebase()
    }

    fun detach() {
        activity = null
        rebase()
    }

    private fun rebase() {
        baseContext = themed(activity ?: applicationContext, dark)
    }
}

private fun themed(base: Context, dark: Boolean): Context =
    ContextThemeWrapper(base, if (dark) R.style.WebViewCompose_Dark else R.style.WebViewCompose_Light)

/** The Activity this context belongs to, through any wrappers, or null. */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
