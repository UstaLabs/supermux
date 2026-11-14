package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #155: a Browser tab's routes (the by-port template and the previews) are asked at the same time, once, and kept until a
// preview changes or the socket reconnects.
class HostStorePreviewRoutesTest {
    private class Broker(var port: Int = 5173, var failProxies: Boolean = false) {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inFlight = AtomicInteger()
        var bothAtOnce = false
        var hold: CompletableDeferred<Unit>? = null

        fun store(): HostStore {
            val engine = MockEngine { req ->
                val path = req.url.encodedPath
                asked += path
                if (inFlight.incrementAndGet() == 2) bothAtOnce = true
                try {
                    hold?.await()
                    delay(50)
                    val h = headersOf(HttpHeaders.ContentType, "application/json")
                    when (path) {
                        "/proxies/by-port" -> respond("""{"url":"https://h.test/p/{port}/"}""", HttpStatusCode.OK, h)
                        "/proxies" -> if (failProxies) respond("{}", HttpStatusCode.InternalServerError, h)
                            else respond("""[{"domain":"p-$port","port":$port,"url":"https://h.test/p/p-$port/"}]""", HttpStatusCode.OK, h)
                        else -> respond("{}", HttpStatusCode.OK, h)
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            return HostStore(
                baseUrl = "ws://test:9898", token = "t", scope = CoroutineScope(Dispatchers.Default), deps = testDeps(),
                connectOnInit = false, apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
            )
        }
    }

    @Test fun bothAreAskedAtTheSameTimeOnceAndKept() = runBlocking {
        val broker = Broker()
        val app = broker.store()
        val routes = app.previewRoutes()
        assertEquals("https://h.test/p/{port}/", routes.byPort)
        assertEquals(listOf(5173), routes.proxies!!.map { it.port })
        assertTrue(broker.bothAtOnce, "the two lookups overlap")
        app.previewRoutes()
        app.previewRoutes()
        assertEquals(2, broker.asked.size, "kept, not asked again: ${broker.asked}")
    }

    @Test fun aPreviewChangeAsksAgainBehindTheKeptAnswer() = runBlocking {
        val broker = Broker()
        val app = broker.store()
        app.previewRoutes()
        broker.port = 3000
        broker.hold = CompletableDeferred()
        app.reduce(ServerFrame.ProxyCreated())
        assertEquals(listOf(5173), app.previewRoutes().proxies!!.map { it.port }, "the kept answer while the new one is asked")
        broker.hold!!.complete(Unit)
        withTimeout(5_000) { while (app.previewRoutes().proxies!!.single().port != 3000) delay(20) }
    }

    @Test fun nothingIsAskedOnAChangeBeforeATabHasAsked() = runBlocking {
        val broker = Broker()
        val app = broker.store()
        app.reduce(ServerFrame.ProxyRemoved("p-5173"))
        delay(200)
        assertTrue(broker.asked.isEmpty(), "a host whose Browser was never opened asks for nothing")
    }

    @Test fun aFailedLookupIsNotKept() = runBlocking {
        val broker = Broker(failProxies = true)
        val app = broker.store()
        assertNull(app.previewRoutes().proxies)
        broker.failProxies = false
        assertEquals(listOf(5173), app.previewRoutes().proxies!!.map { it.port })
    }
}
