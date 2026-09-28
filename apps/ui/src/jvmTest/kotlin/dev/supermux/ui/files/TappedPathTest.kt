package dev.supermux.ui.files

import dev.supermux.fs.FileSystemService
import dev.supermux.fs.FsStat
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TappedPathTest {
    @Test fun onlyA404MeansMissing() {
        assertTrue(statSaysMissing(Result.failure(FsException(404, "ENOENT"))))
        assertFalse(statSaysMissing(Result.failure(FsException(403, "EACCES"))))
        assertFalse(statSaysMissing(Result.failure(RuntimeException("offline"))))
        assertFalse(statSaysMissing(Result.success(FsStat(name = "a.kt", type = "file", real = "/w/a.kt"))))
    }

    private fun service(status: HttpStatusCode, body: String) = FileSystemService(
        BrokerApi("http://h", "t", HttpClient(MockEngine { respond(body, status, headersOf("Content-Type", "application/json")) })),
        send = { },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test fun aMissingFileOnTheHostIsReported() = runBlocking {
        assertTrue(tappedFileMissing(service(HttpStatusCode.NotFound, """{"error":"ENOENT","message":"no"}"""), "/w/gone.kt"))
        assertFalse(tappedFileMissing(service(HttpStatusCode.OK, """{"name":"a.kt","type":"file","real":"/w/a.kt"}"""), "/w/a.kt"))
        assertFalse(tappedFileMissing(null, "/w/a.kt"))
    }

    @Test fun theNoticeNamesThePath() {
        assertEquals("File not found: src/a.kt", fileNotFoundNotice("src/a.kt"))
    }
}
