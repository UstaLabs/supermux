package dev.supermux.state

import dev.supermux.net.SpawnRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Card #75: a session start (POST /sessions) answers once the worktree is made and the agent is up,
 * up to ~26 s on a busy host. On desktop and Android (Ktor CIO, 15 s per request by default) the
 * launcher gave up while the broker went on; a start now rides a 45 s client, nothing else does.
 */
class HostStoreSessionStartTimeoutTest {
    @Test fun aSessionStartWaitsUpTo45sOnDesktopAndAndroid() {
        assertEquals(45_000L, SESSION_START_TIMEOUT_MS)
    }

    @Test fun onlyPostSessionsRidesTheSessionStartClient() = runBlocking {
        val byTimeout = mutableMapOf<Long?, MutableList<String>>()
        val deps = HostStoreDeps(
            httpFactory = { timeout ->
                HttpClient(MockEngine { req ->
                    val call = "${req.method.value} ${req.url.encodedPath}"
                    synchronized(byTimeout) { byTimeout.getOrPut(timeout) { mutableListOf() } += call }
                    val body = when (call) {
                        "POST /paths/validate" -> """{"ok":true,"path":"/w"}"""
                        "POST /sessions" -> """{"id":"s1","name":"New","workdir":"/w","agent":"claude"}"""
                        else -> "{}"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                })
            },
            settings = FakeSettingsStore(),
            clock = FixedClock(),
        )
        val store = HostStore("http://h", "t", CoroutineScope(Dispatchers.Default), deps, connectOnInit = false)
        assertEquals(
            "s1",
            store.createSessionWithFirstMessageOrThrow(
                workdir = "/w", agent = "claude", model = null, reasoningLevel = null, text = "hi",
                staged = emptyList(), worktree = true, baseBranch = null,
            ),
        )
        assertEquals("s1", store.spawnRequest(SpawnRequest(workdir = "/w")))
        assertEquals(listOf("POST /sessions", "POST /sessions"), byTimeout[45_000L]?.toList())
        assertEquals(listOf("POST /paths/validate"), byTimeout[null]?.toList())
        store.close()
    }
}
