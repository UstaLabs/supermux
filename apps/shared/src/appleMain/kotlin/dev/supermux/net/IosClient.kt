package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/**
 * iOS Ktor client (Darwin engine + WebSockets), mirroring the Android app's
 * `HttpClient(CIO) { install(WebSockets) }`. Exposed to Swift as
 * `IosClientKt.iosHttpClient()` so the app need not configure Ktor from Swift.
 */
fun iosHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets) {
        // The broker's initial snapshot can exceed the Darwin WebSocket's default
        // 1 MB maximumMessageSize ("Message too long" / EMSGSIZE). Raise it.
        maxFrameSize = 64L * 1024 * 1024
    }
}
