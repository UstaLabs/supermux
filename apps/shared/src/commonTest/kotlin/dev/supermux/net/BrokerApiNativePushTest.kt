package dev.supermux.net

import dev.supermux.push.NativePushRegistration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [BrokerApi.registerNativePush]: one phone's push registration with one broker, and the
 * report of how it ended that goes to the broker's log (card #86). From 10-27 every phone
 * failed this and only the phone's own log said so.
 */
class BrokerApiNativePushTest {
    private val broker = "http://broker.example"
    private val tunnel = "https://h-x.relay.example"
    private val pushRelay = "https://push.example"

    private class Reply(val status: HttpStatusCode, val body: String)

    /** Answers by "METHOD url"; anything unlisted is a 404. Records every request. */
    private fun api(sink: MutableList<HttpRequestData>, routes: Map<String, Reply>): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            val reply = routes["${req.method.value} ${req.url}"] ?: Reply(HttpStatusCode.NotFound, "not found")
            respond(ByteReadChannel(reply.body), reply.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi(broker, "tok", HttpClient(engine))
    }

    private fun me(body: String) = "GET $broker/me" to Reply(HttpStatusCode.OK, body)
    private val meWithBoth = me("""{"paired":true,"device":"fold","relayUrl":"$tunnel","pushRelayUrl":"$pushRelay"}""")
    private val relayIssues = "POST $pushRelay/register" to Reply(HttpStatusCode.Accepted, """{"status":"pending","routingToken":"rt-1"}""")
    private val brokerTakesDevice = "POST $broker/push/device" to Reply(HttpStatusCode.OK, "{}")
    private val brokerTakesLogs = "POST $broker/client-logs" to Reply(HttpStatusCode.OK, """{"ok":true}""")

    private fun HttpRequestData.bodyText(): String =
        when (val c = this.body) {
            is io.ktor.http.content.TextContent -> c.text
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> ""
        }

    private fun List<HttpRequestData>.to(url: String) = filter { it.url.toString() == url }
    private fun List<HttpRequestData>.report(): String = single { it.url.toString() == "$broker/client-logs" }.bodyText()

    @Test fun registers_at_the_push_relay_never_the_tunnel_and_reports_it() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val r = api(reqs, mapOf(meWithBoth, relayIssues, brokerTakesDevice, brokerTakesLogs))
            .registerNativePush("android", "fcm-token", "PUBKEY")

        val registered = assertIs<NativePushRegistration.Registered>(r)
        assertEquals("rt-1", registered.routingToken)
        assertEquals(pushRelay, registered.relayUrl)
        assertTrue(reqs.none { it.url.host == "h-x.relay.example" }, "nothing goes to the tunnel")
        val device = reqs.to("$broker/push/device").single()
        assertEquals(HttpMethod.Post, device.method)
        assertTrue(device.bodyText().contains("\"routingToken\":\"rt-1\""))
        assertTrue(device.bodyText().contains("\"pubkey\":\"PUBKEY\""))
        val report = reqs.report()
        assertTrue(report.contains("\"category\":\"push\""), report)
        assertTrue(report.contains("\"event\":\"push_registered\""), report)
        assertTrue(report.contains("\"platform\":\"android\""), report)
        assertTrue(report.contains("\"relay\":\"$pushRelay\""), report)
        assertFalse(report.contains("rt-1"), "the routing token is a credential and stays out of logs")
        assertFalse(report.contains("fcm-token"), "the push token stays out of logs")
    }

    @Test fun a_relay_that_refuses_is_reported_with_its_stage_and_error() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val r = api(reqs, mapOf(meWithBoth, brokerTakesLogs)).registerNativePush("android", "fcm-token", "PUBKEY")

        val failed = assertIs<NativePushRegistration.Failed>(r)
        assertEquals("relay", failed.stage)
        assertTrue(failed.error.contains("HTTP 404"), failed.error)
        assertTrue(reqs.to("$broker/push/device").isEmpty())
        val report = reqs.report()
        assertTrue(report.contains("\"event\":\"push_register_failed\""), report)
        assertTrue(report.contains("\"stage\":\"relay\""), report)
        assertTrue(report.contains("HTTP 404"), report)
    }

    @Test fun a_broker_that_refuses_the_device_is_reported() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val refuses = "POST $broker/push/device" to Reply(HttpStatusCode.InternalServerError, "boom")
        val r = api(reqs, mapOf(meWithBoth, relayIssues, refuses, brokerTakesLogs)).registerNativePush("ios", "apns-token", "PUBKEY")

        assertEquals("broker", assertIs<NativePushRegistration.Failed>(r).stage)
        assertTrue(reqs.report().contains("\"stage\":\"broker\""))
    }

    @Test fun a_broker_without_a_push_relay_is_reported_as_skipped() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val r = api(reqs, mapOf(me("""{"paired":true,"device":"fold"}"""), brokerTakesLogs)).registerNativePush("android", "fcm-token", "PUBKEY")

        assertIs<NativePushRegistration.NoPushRelay>(r)
        val report = reqs.report()
        assertTrue(report.contains("\"event\":\"push_register_skipped\""), report)
        assertTrue(report.contains("\"reason\":\"no_push_relay\""), report)
    }

    @Test fun an_old_relay_without_a_routing_token_is_reported_as_pending() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val oldRelay = "POST $pushRelay/register" to Reply(HttpStatusCode.Accepted, """{"status":"pending"}""")
        val r = api(reqs, mapOf(meWithBoth, oldRelay, brokerTakesLogs)).registerNativePush("android", "fcm-token", "PUBKEY")

        assertIs<NativePushRegistration.AwaitingBootstrap>(r)
        assertTrue(reqs.to("$broker/push/device").isEmpty())
        assertTrue(reqs.report().contains("\"event\":\"push_register_pending\""))
    }

    @Test fun a_report_the_broker_refuses_does_not_undo_the_registration() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val logsDown = "POST $broker/client-logs" to Reply(HttpStatusCode.InternalServerError, "down")
        val r = api(reqs, mapOf(meWithBoth, relayIssues, brokerTakesDevice, logsDown)).registerNativePush("android", "fcm-token", "PUBKEY")

        assertIs<NativePushRegistration.Registered>(r)
    }
}
