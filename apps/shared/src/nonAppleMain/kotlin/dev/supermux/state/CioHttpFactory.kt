package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.WebSocketDeflateExtension

/** CIO-backed factory shared by desktop and Android. */
fun cioHttpFactory(): (Long?) -> HttpClient = { timeoutMs ->
    HttpClient(CIO) {
        install(WebSockets) {
            // Offer permessage-deflate: the broker compresses when asked, and without it the
            // snapshot (and every frame after) crosses the network uncompressed — ~4x the bytes.
            extensions { install(WebSocketDeflateExtension) }
        }
        if (timeoutMs != null) engine { requestTimeout = timeoutMs }
    }
}
