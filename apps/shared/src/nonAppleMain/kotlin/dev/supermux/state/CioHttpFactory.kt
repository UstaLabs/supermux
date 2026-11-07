package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.plugins.compression.ContentEncoding
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
        // Ask for gzip: the broker compresses JSON answers of 8 KB or more (card #54) — a chat's
        // history and extras, the archive list — which otherwise cross the network 4–5× larger.
        install(ContentEncoding) { gzip() }
        if (timeoutMs != null) engine { requestTimeout = timeoutMs }
    }
}
