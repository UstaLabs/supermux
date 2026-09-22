package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * The `HostStoreDeps.httpFactory` for iOS: [iosHttpClient] plus the per-call timeout the shared
 * stores ask for.
 *
 * `iosHttpClient()` exists already and takes no argument, because Swift only ever needed one client
 * with the engine's default timeout. `FleetStore`/`HostStore` need a factory instead: one long-lived
 * client for the WebSocket and the ordinary requests, and a separate one for the dictation POST,
 * which uploads a voice clip and transcribes it server-side and routinely takes longer than
 * NSURLSession's 60-second default. A timed-out dictation is a lost recording, so the seam is
 * `(timeoutMs: Long?) -> HttpClient` on every platform and iOS must honour it rather than ignore it.
 *
 * The timeout is set on the Darwin engine's own request configuration (`timeoutIntervalForRequest`),
 * not through Ktor's `HttpTimeout` plugin: NSURLSession enforces its own deadline regardless, so
 * configuring only the plugin would leave the engine cancelling the upload underneath it.
 *
 * Everything else — cookies off, the `supermux_native=1` sentinel, the 64 MB WebSocket frame — is
 * inherited from [iosHttpClient] and explained there; it is duplicated in shape only because the
 * Darwin builder is not reusable across two configurations.
 */
fun iosHttpFactory(): (Long?) -> HttpClient = { timeoutMs ->
    HttpClient(Darwin) {
        engine {
            configureSession {
                HTTPShouldSetCookies = false
                HTTPCookieStorage = null
            }
            if (timeoutMs != null) {
                configureRequest {
                    setTimeoutInterval(timeoutMs / 1000.0)
                }
            }
        }
        defaultRequest { header(HttpHeaders.Cookie, "supermux_native=1") }
        install(WebSockets) {
            maxFrameSize = 64L * 1024 * 1024
        }
    }
}
