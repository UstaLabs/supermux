package dev.supermux.android.push

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Card #86: a bootstrap message's registration ran on the FCM service's own client. Android
 * destroys the service as soon as onMessageReceived returns and onDestroy closed that client, so
 * the registration failed with "Parent job is Completed" and never reached the broker. Seen on
 * the Fold at 01:26 on 11-07.
 */
class DetachedRegistrationTest {
    private fun broker(posted: MutableList<String>) = MockEngine { req ->
        posted += "${req.method.value} ${req.url}"
        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test fun the_registration_reaches_the_broker_after_the_service_client_is_closed() = runTest {
        val posted = mutableListOf<String>()
        HttpClient(broker(posted)).close() // the FCM service is gone, and its client with it

        launchDetached(this, newClient = { HttpClient(broker(posted)) }) { http ->
            BrokerApi("http://broker.example", "tok", http).registerPushDevice("android", "rt-1", "PK")
        }.join()

        assertEquals(listOf("POST http://broker.example/push/device"), posted)
    }

    @Test fun the_registrations_own_client_is_closed_when_it_ends() = runTest {
        var used: HttpClient? = null
        launchDetached(this, newClient = { HttpClient(broker(mutableListOf())) }) { used = it }.join()
        assertFalse(used!!.isActive)
    }
}
