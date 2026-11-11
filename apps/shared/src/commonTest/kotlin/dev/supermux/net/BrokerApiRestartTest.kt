package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `POST /system/restart` as the machine page reads it (#97 Step 2): accepted, can't restart itself (409), failed. */
class BrokerApiRestartTest {

    private val seen = mutableListOf<Pair<HttpMethod, String>>()

    private fun api(status: HttpStatusCode, body: String = "{}"): BrokerApi {
        val engine = MockEngine { req ->
            seen += req.method to req.url.encodedPath
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    @Test fun an_accepted_restart() = runTest {
        assertEquals(BrokerRestart.Accepted, api(HttpStatusCode.OK, """{"ok":true}""").restartBrokerResult())
        assertEquals(HttpMethod.Post to "/system/restart", seen.single())
    }

    @Test fun a_409_is_a_broker_that_cannot_restart_itself() = runTest {
        val body = """{"ok":false,"error":"the broker is not running under a service manager, so it can't restart itself"}"""
        assertEquals(BrokerRestart.CantRestartItself, api(HttpStatusCode.Conflict, body).restartBrokerResult())
    }

    @Test fun anything_else_failed() = runTest {
        assertEquals(BrokerRestart.Failed, api(HttpStatusCode.InternalServerError).restartBrokerResult())
        assertEquals(BrokerRestart.Failed, api(HttpStatusCode.Unauthorized).restartBrokerResult())
    }
}
