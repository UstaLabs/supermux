package dev.supermux.web.auth

import dev.supermux.net.BrokerApi
import dev.supermux.net.MeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** The three answers the browser bootstrap can get out of the broker. */
sealed interface SessionState {
    /** A valid `cmux_token` cookie is in play; [deviceName] is what the broker calls this browser. */
    data class Paired(val deviceName: String?) : SessionState
    /** The broker is reachable and says this browser is not (and may not become) a device. */
    data class Unpaired(val reason: String) : SessionState
    /** The broker did not answer at all — a transport failure, not a verdict about pairing. */
    data class Offline(val error: String) : SessionState
}

/**
 * The browser's pairing gate — the web twin of iOS's `isPaired()`, which cannot work here because
 * the credential is an HttpOnly cookie Kotlin can never read. Mirrors `App.vue`:
 *  1. `GET /me` — paired ⇒ start.
 *  2. Otherwise `POST /pair/claim` with no secret: 200 ⇒ the broker set the cookie (trust on
 *     first connect, brand-new broker); 403 ⇒ someone else already paired, show the pair screen.
 * Both requests carry no bearer (the [BrokerApi] token is blank), so the cookie is the only
 * credential in play.
 *
 * The `/me` leg deliberately does NOT go through [BrokerApi.me]: `BrokerApi.decode` turns every
 * non-2xx into a [CancellationException] (it must, so a 401 cannot crash the iOS process), which
 * makes "unauthorized" and "the network is down" the same throwable. Those two must lead to
 * opposite screens, so this reads the status off the raw [HttpClient] instead: a 401/403 is an
 * answer ("not paired — try claiming"), and only a thrown transport error is [Offline].
 * [BrokerApi.claimSecretless] already reports a 403 as `paired=false` without throwing, so the
 * claim leg does go through [BrokerApi].
 */
class CookieSession(baseUrl: String = window.location.origin, private val http: HttpClient) {
    private val base = baseUrl.trimEnd('/')
    private val api = BrokerApi(base, "", http)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun probe(deviceName: String = "browser"): SessionState {
        val me = try {
            val res = http.get("$base/me")
            if (res.status.value in 200..299) {
                runCatching { json.decodeFromString<MeResult>(res.bodyAsText()) }.getOrNull()
            } else {
                null // 401/403 (or anything else the broker answered): not paired — try to claim.
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            return SessionState.Offline(e.message ?: "offline")
        }
        if (me?.paired == true) return SessionState.Paired(me.device)

        val claim = try {
            api.claimSecretless(deviceName)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            return SessionState.Offline(e.message ?: "offline")
        }
        return if (claim.paired) SessionState.Paired(claim.name.ifBlank { deviceName })
        else SessionState.Unpaired(claim.error ?: "not paired")
    }

    /** `POST /logout` expires the cookie; the page reloads into the pair screen. */
    suspend fun logout() {
        runCatching { api.logout() }
        window.location.reload()
    }
}
