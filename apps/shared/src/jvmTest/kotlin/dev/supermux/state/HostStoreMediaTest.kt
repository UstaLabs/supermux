package dev.supermux.state

import kotlin.test.assertNull
import dev.supermux.media.FakeMediaStore
import dev.supermux.media.MediaCache
import dev.supermux.media.MediaKey
import dev.supermux.net.BrokerApi
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.proto.Attachment
import dev.supermux.work.hostFileId
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
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostStoreMediaTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val png = byteArrayOf(1, 2, 3)
    private val att = Attachment(file_id = id, kind = "image", mime = "image/png", name = "a.png")

    private val report = "/home/u/report.pdf"
    private val pdf = byteArrayOf(9, 8, 7, 6)

    /**
     * A host whose `/fs/stat` of [report] answers [pdf]'s size with each of [mtimes] in turn, and whose `/fs/raw`
     * serves [pdf]; [seen] records every request.
     */
    private fun hostWithAFile(
        cache: MediaCache?,
        vararg mtimes: Long,
        seen: MutableList<String> = mutableListOf(),
        browser: BrowserDownload? = null,
    ): HostStore {
        val stats = ArrayDeque(mtimes.toList())
        val http = HttpClient(
            MockEngine { req ->
                synchronized(seen) { seen += "${req.method.value} ${req.url.encodedPath}" }
                when (req.url.encodedPath) {
                    "/fs/stat" -> respond(
                        """{"name":"report.pdf","type":"file","size":${pdf.size},"mtime":${synchronized(stats) { stats.removeFirst() }},"real":"$report"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    "/fs/raw" -> respond(ByteReadChannel(pdf), HttpStatusCode.OK)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        return HostStore(
            "http://h", "t", CoroutineScope(Dispatchers.Default), testDeps(http = http, media = cache, browserDownload = browser),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
    }

    private fun store(cache: MediaCache?, seen: MutableList<String>, browser: BrowserDownload? = null): HostStore {
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
            "http://h", "t", CoroutineScope(Dispatchers.Default), testDeps(http = http, media = cache, browserDownload = browser),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
    }

    @Test fun anAttachmentDownloadsToAFileInTheStore() = runTest {
        val disk = FakeMediaStore(hasFiles = true)
        val app = store(MediaCache(disk, this), mutableListOf())
        app.bindMedia("rec-1")
        assertEquals("att:rec-1/$id", app.media.downloadKey(att))
        val got = assertNotNull(app.media.download(att) { _, _ -> })
        assertEquals(assertNotNull(disk.path(assertNotNull(MediaKey.of("rec-1", id, "a.png", "image/png")))), got.path)
        assertEquals(png.size.toLong(), got.size)
        assertEquals("a.png", got.name)
        assertEquals("image/png", got.mime)
    }

    // The web has no files: the browser fetches the attachment into a Blob of its own, and the bytes never pass
    // through this app.
    @Test fun onTheWebTheBrowserDownloadsTheAttachment() = runTest {
        val seen = mutableListOf<String>()
        val blob = Any()
        var asked: Pair<String, Map<String, String>>? = null
        val browser = BrowserDownload { url, headers, progress ->
            asked = url to headers
            progress(3, 3)
            blob to 3L
        }
        val app = store(MediaCache(FakeMediaStore(hasFiles = false), this), seen, browser)
        app.bindMedia("rec-1")
        assertEquals("att:rec-1/$id", app.media.downloadKey(att))
        val got = assertNotNull(app.media.download(att) { _, _ -> })
        assertEquals("http://h/files/$id" to mapOf("Authorization" to "Bearer t"), asked)
        assertSame(blob, got.handle)
        assertNull(got.path)
        assertEquals(3L, got.size)
        assertTrue(seen.isEmpty(), "seen: $seen")
    }

    // The file is stored under its (path, mtime, size): kept only when what came is the whole of the version
    // the first stat saw, which a second stat after the transfer confirms.
    @Test fun aHostFileIsKeptWhenItDidNotChangeWhileItCame() = runTest {
        val disk = FakeMediaStore(hasFiles = true)
        val app = hostWithAFile(MediaCache(disk, this), 1000, 1000)
        app.bindMedia("rec-1")
        val (key, source) = assertNotNull(app.hostFileDownload(report, "v1"))
        assertEquals("fs:rec-1:$report@v1", key)
        val heard = mutableListOf<Pair<Long, Long?>>()
        val got = assertNotNull(source.fetch { received, total -> heard += received to total })
        val stored = "rec-1/${hostFileId(report, 1000, pdf.size.toLong())}.pdf"
        assertContentEquals(pdf, disk.entries[stored])
        assertEquals("/cache/$stored", got.path)
        assertEquals(pdf.size.toLong(), got.size)
        assertEquals("report.pdf", got.name)
        assertEquals("application/pdf", got.mime)
        assertEquals(4L to 4L, heard.last())
    }

    @Test fun aHostFileThatChangedWhileItCameIsNotKept() = runTest {
        val disk = FakeMediaStore(hasFiles = true)
        val app = hostWithAFile(MediaCache(disk, this), 1000, 2000)
        app.bindMedia("rec-1")
        val (_, source) = assertNotNull(app.hostFileDownload(report, "v1"))
        assertNull(source.fetch { _, _ -> })
        assertTrue(disk.entries.isEmpty(), "stored: ${disk.entries.keys}")
    }

    @Test fun onTheWebTheBrowserDownloadsTheHostFile() = runTest {
        val seen = mutableListOf<String>()
        val blob = Any()
        var asked: Pair<String, Map<String, String>>? = null
        val browser = BrowserDownload { url, headers, _ ->
            asked = url to headers
            blob to 4L
        }
        val app = hostWithAFile(MediaCache(FakeMediaStore(hasFiles = false), this), seen = seen, browser = browser)
        app.bindMedia("rec-1")
        val (_, source) = assertNotNull(app.hostFileDownload(report, "v1"))
        val got = assertNotNull(source.fetch { _, _ -> })
        assertEquals("http://h/fs/raw?path=%2Fhome%2Fu%2Freport.pdf" to mapOf("Authorization" to "Bearer t"), asked)
        assertSame(blob, got.handle)
        assertEquals("report.pdf", got.name)
        assertTrue(seen.isEmpty(), "seen: $seen")
    }

    @Test fun withoutAStoreOrABrowserThereIsNoDownload() = runTest {
        val app = hostWithAFile(null)
        app.bindMedia("rec-1")
        assertNull(app.media.downloadKey(att))
        assertNull(app.hostFileDownload(report, "v1"))
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

    @Test fun aClosedHostNoLongerUsesTheCache() = runTest {
        val seen = mutableListOf<String>()
        val disk = FakeMediaStore(hasFiles = true)
        val app = store(MediaCache(disk, this), seen)
        app.bindMedia("rec-1")
        app.close()
        assertNull(app.media.peek(att))
        assertNull(app.media.playableUri(att))
        app.media.bytes(att)
        testScheduler.advanceUntilIdle()
        assertTrue(disk.writes.isEmpty(), "writes: ${disk.writes}")
    }
}
