package dev.supermux.webview.engine.android

import android.webkit.WebView
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The inspector switch. Android has one per process (`WebView.setWebContentsDebuggingEnabled`), not one per page, so it
 * is on while any lease asks for `inspectable`, which makes every page of the app inspectable meanwhile. It is turned
 * off again only if this library turned it on: an app that enabled debugging itself keeps it. Main thread only.
 */
internal object Inspector {
    private val wanting: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private var enabledHere = false

    fun want(owner: Any, inspectable: Boolean) {
        val changed = if (inspectable) wanting.add(owner) else wanting.remove(owner)
        if (!changed) return
        if (wanting.isNotEmpty() && !enabledHere) {
            WebView.setWebContentsDebuggingEnabled(true)
            enabledHere = true
        } else if (wanting.isEmpty() && enabledHere) {
            WebView.setWebContentsDebuggingEnabled(false)
            enabledHere = false
        }
    }
}
