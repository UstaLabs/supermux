package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** CIO-backed factory shared by desktop and Android. */
fun cioHttpFactory(): (Long?) -> HttpClient = { timeoutMs ->
    HttpClient(CIO) {
        install(WebSockets)
        if (timeoutMs != null) engine { requestTimeout = timeoutMs }
    }
}
