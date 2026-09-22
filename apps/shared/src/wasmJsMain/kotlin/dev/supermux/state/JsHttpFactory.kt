package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Browser-backed factory, the wasm twin of `cioHttpFactory`. Ktor's Js engine is `fetch` +
 * the browser `WebSocket`, both of which attach the same-origin `cmux_token` cookie themselves —
 * that is the whole auth story on the web (see `SecureTokenStore.wasmJs.kt`).
 *
 * The Js engine has no `requestTimeout` engine knob, so the CIO factory's timeout argument is
 * honoured with the common `HttpTimeout` plugin instead.
 */
fun jsHttpFactory(): (Long?) -> HttpClient = { timeoutMs ->
    HttpClient(Js) {
        install(WebSockets)
        if (timeoutMs != null) install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
    }
}
