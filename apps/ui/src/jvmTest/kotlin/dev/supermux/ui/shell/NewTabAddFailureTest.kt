package dev.supermux.ui.shell

import dev.supermux.ui.chat.testHostStore
import dev.supermux.workspace.NewViewKind
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** A New tab shows "opening…" until its view exists; a broker that refuses the view must say so. */
class NewTabAddFailureTest {
    @Test fun aRefusedAddSaysSo() {
        val failed = CompletableDeferred<Unit>()
        val store = testHostStore { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/views")) {
                respond("""{"error":"unknown view kind"}""", HttpStatusCode.BadRequest)
            } else {
                respond("{}", HttpStatusCode.OK)
            }
        }
        store.addWorkspaceView("w", NewViewKind.TERMINAL, "g", onFailed = { failed.complete(Unit) })
        runBlocking { withTimeout(5_000) { failed.await() } }
    }
}
