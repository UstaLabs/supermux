// #165: what an applet widget's button needs to reach its host without the app: the host's address and this device's
// key, kept in the keychain group the app shares with its extensions (the app's default group, which the widget
// extension also lists). The widget's Swift side (AppletWidgetDoc.swift, WidgetHostAccess) reads the same item.
package dev.supermux.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object WidgetCredentials {
    private const val SERVICE = "dev.supermux.widgets"

    /** Keeps [hostId]'s [url] and [token] for its widgets; rewritten only when they changed. */
    fun save(hostId: String, url: String, token: String) {
        val value = buildJsonObject { put("url", url); put("token", token) }.toString()
        if (IosKeychain.get(SERVICE, hostId) != value) IosKeychain.put(SERVICE, hostId, value)
    }
}
