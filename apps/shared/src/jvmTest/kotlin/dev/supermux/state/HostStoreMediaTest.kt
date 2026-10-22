package dev.supermux.state

import dev.supermux.media.FakeMediaStore
import dev.supermux.media.MediaCache
import dev.supermux.net.BrokerApi
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.proto.Attachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostStoreMediaTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val png = byteArrayOf(1, 2, 3)
    private val att = Attachment(file_id = id, kind = "image", mime = "image/png", name = "a.png")

    private fun store(cache: MediaCache?, seen: MutableList<String>): HostStore {
        val http = HttpClient(
            MockEngine { req ->
                synchronized(seen) { seen += "${req.method.value} ${req.url.encodedPath}" }
                if (req.url.encodedPath == "/upload") {
                    respond(
                        ByteReadChannel("""{"file_id":"$id","size":3,"mime":"image/png","name":"a.png"}"""),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(ByteReadChannel(png), HttpStatusCode.OK)
                }
            },
        )
        return HostStore(
            "http://h", "t", CoroutineScope(Dispatchers.Default), testDeps(http = http, media = cache),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
    }

    @Test fun withoutACacheTheChatReadsStraightFromTheBroker() = runTest {
        val seen = mutableListOf<String>()
        val app = store(null, seen)
        assertContentEquals(png, app.media.bytes(att))
        assertContentEquals(png, app.media.bytes(att))
        assertEquals(listOf("GET /files/$id", "GET /files/$id"), seen)
    }

    @Test fun aBoundCacheServesTheSecondReadFromMemory() = runTest {
        val seen = mutableListOf<String>()
        val app = store(MediaCache(FakeMediaStore(hasFiles = false), this), seen)
        app.bindMedia("rec-1")
        assertContentEquals(png, app.media.bytes(att))
        assertContentEquals(png, app.media.peek(att))
        assertContentEquals(png, app.media.bytes(att))
        assertEquals(listOf("GET /files/$id"), seen)
    }

    @Test fun anUploadIsSeededSoItNeverDownloads() = runTest {
        val seen = mutableListOf<String>()
        val disk = FakeMediaStore(hasFiles = false)
        val app = store(MediaCache(disk, this), seen)
        app.bindMedia("rec-1")
        assertEquals(id, app.uploadResumable("s1", ByteArrayChunkSource(png), "a.png", "image/png") { _, _ -> })
        testScheduler.advanceUntilIdle()
        assertContentEquals(png, disk.entries["rec-1/$id.png"])
        assertContentEquals(png, app.media.bytes(att))
        assertTrue(seen.none { it.startsWith("GET /files") }, "seen: $seen")
    }
}
