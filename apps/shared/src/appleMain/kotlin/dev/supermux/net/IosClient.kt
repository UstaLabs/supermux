package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

/**
 * iOS Ktor client (Darwin engine + WebSockets), mirroring the Android app's
 * `HttpClient(CIO) { install(WebSockets) }`. Exposed to Swift as
 * `IosClientKt.iosHttpClient()` so the app need not configure Ktor from Swift.
 */
fun iosHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            // Native clients authenticate every request with an explicit Bearer token. Never
            // inherit a stale cmux_token browser cookie: the broker intentionally gives cookies
            // precedence, which would turn a valid native credential into intermittent 401s.
            HTTPShouldSetCookies = false
            HTTPCookieStorage = null
        }
    }
    // An explicit Cookie header overrides NSURLSession's ambient cookie jar. The broker ignores
    // this sentinel and authenticates the Bearer token, so an old cmux_token can never mask a
    // valid native credential during repeated settings/onboarding requests.
    defaultRequest { header(HttpHeaders.Cookie, "supermux_native=1") }
    install(WebSockets) {
        // The broker's initial snapshot can exceed the Darwin WebSocket's default
        // 1 MB maximumMessageSize ("Message too long" / EMSGSIZE). Raise it.
        maxFrameSize = 64L * 1024 * 1024
    }
}

private var crashGuardInstalled = false

/**
 * Install a Kotlin/Native unhandled-exception backstop so the app does not SIGABRT.
 *
 * ktor-client-darwin surfaces connection errors (e.g. the broker restarting or the
 * WebSocket dropping) on an internal coroutine that has no CoroutineExceptionHandler.
 * Kotlin/Native then routes that unhandled coroutine exception to
 * `terminateWithUnhandledException` → SIGABRT — uncatchable from Swift `try?` or from
 * `BrokerClient.run()`'s own try/catch (it runs on a different coroutine). That is the
 * observed "app cannot be open" launch crash: a connection error at startup kills the
 * process before the UI is usable.
 *
 * The hook logs and RETURNS (it deliberately does NOT call
 * `terminateWithUnhandledException`), so the process survives and
 * `BrokerClient.run()`'s reconnect loop recovers. Call once, as early as possible
 * (app init), before any networking starts.
 *
 * NOTE: this is a global backstop — it keeps the app alive after ANY otherwise-fatal
 * unhandled Kotlin exception. To still capture crash reports, wire a reporter
 * (Crashlytics/Sentry) inside the hook; for now it logs to the device console.
 */
@OptIn(ExperimentalNativeApi::class)
fun installIosCrashGuard() {
    if (crashGuardInstalled) return
    crashGuardInstalled = true
    setUnhandledExceptionHook { t ->
        println("[supermux] kept app alive after unhandled exception: $t")
        t.printStackTrace()
    }
}
