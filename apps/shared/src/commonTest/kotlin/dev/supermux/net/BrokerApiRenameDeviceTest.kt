// #97: PATCH /devices/<name> {name} renames a paired device; a refusal reads back as the broker's {error, code}.
package dev.supermux.net

import dev.supermux.state.AccountResult
import dev.supermux.state.accountCall
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrokerApiRenameDeviceTest {
    private fun captured(sink: MutableList<HttpRequestData>, body: String, status: HttpStatusCode = HttpStatusCode.OK): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.text(): String = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    @Test fun renameDevice_patches_the_encoded_name_and_returns_the_device() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val d = captured(reqs, """{"name":"Pixel 9","created_at":"t","last_seen_at":null,"current":true}""")
            .renameDevice("my phone", "Pixel 9")
        assertEquals(DeviceDto(name = "Pixel 9", created_at = "t", last_seen_at = null, current = true), d)
        val r = reqs.single()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("http://h/devices/my%20phone", r.url.toString())
        assertEquals("""{"name":"Pixel 9"}""", r.text())
    }

    @Test fun a_taken_name_fails_with_the_brokers_status_code_and_message() = runTest {
        val api = captured(
            mutableListOf(),
            """{"error":"Another device already has that name.","code":"name_taken"}""",
            HttpStatusCode.Conflict,
        )
        val r = accountCall { api.renameDevice("phone", "laptop") }
        assertEquals(AccountResult.Failed(409, "name_taken", "Another device already has that name."), r)
    }

    @Test fun an_unknown_device_fails_with_404() = runTest {
        val r = accountCall { captured(mutableListOf(), """{"error":"unknown device"}""", HttpStatusCode.NotFound).renameDevice("ghost", "x") }
        assertEquals(404, (r as AccountResult.Failed).status)
    }
}
