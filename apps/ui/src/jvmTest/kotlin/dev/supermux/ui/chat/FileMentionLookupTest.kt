package dev.supermux.ui.chat

import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [lookupMentionFiles]: the `@` menu asks the host's file system exactly what "Go to file…" asks it. */
class FileMentionLookupTest {

    private fun fs(urls: MutableList<Url>, body: String, status: HttpStatusCode = HttpStatusCode.OK) = FileSystemService(
        BrokerApi(
            "http://h", "t",
            HttpClient(
                MockEngine { req ->
                    synchronized(urls) { urls += req.url }
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        ),
        send = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    @Test fun aSearchIsTheGoToFileSearchScopedToTheWorkdir() = runTest {
        val urls = mutableListOf<Url>()
        val body = """[{"path":"/w/src/Composer.kt","name":"Composer.kt","type":"file","score":3,"hits":[7,8]},
                       {"path":"/w/src","name":"src","type":"dir","score":1,"hits":[3]}]"""
        val rows = lookupMentionFiles(fs(urls, body), "/w", MentionLookup.Search("co"))!!
        assertEquals(listOf("src/Composer.kt", "src"), rows.map { it.relativePath })
        assertEquals(listOf(false, true), rows.map { it.isDir })
        assertEquals(listOf(0, 1), rows[0].nameHits)
        val url = urls.single()
        assertEquals("/fs/search", url.encodedPath)
        assertEquals("/w", url.parameters["scope"])
        assertEquals("co", url.parameters["q"])
    }

    @Test fun aFolderIsListedOnTheHost() = runTest {
        val urls = mutableListOf<Url>()
        val body = """{"path":"/w/src","version":"b:1","entries":[{"name":"b.kt","type":"file"},{"name":"ui","type":"dir"}]}"""
        val rows = lookupMentionFiles(fs(urls, body), "/w", MentionLookup.Folder("src"))!!
        assertEquals(listOf("src/ui", "src/b.kt"), rows.map { it.relativePath })
        val url = urls.single()
        assertEquals("/fs/list", url.encodedPath)
        assertEquals("/w/src", url.parameters["path"])
    }

    @Test fun aBareAtListsTheWorkdirItself() = runTest {
        val urls = mutableListOf<Url>()
        lookupMentionFiles(fs(urls, """{"path":"/w","version":"b:1","entries":[]}"""), "/w", MentionLookup.Folder(""))
        assertEquals("/w", urls.single().parameters["path"])
    }

    @Test fun aWindowsWorkdirSpelledInAnotherCaseStillFindsItsFiles() = runTest {
        // The session says c:\users\me\app; the host's search answers under its real path.
        val body = """[{"path":"C:\\Users\\me\\app/src/Main.kt","name":"Main.kt","type":"file","score":1,"hits":[20]}]"""
        val rows = lookupMentionFiles(fs(mutableListOf(), body), "c:\\users\\me\\app", MentionLookup.Search("main"))!!
        assertEquals(listOf("src/Main.kt"), rows.map { it.relativePath })
    }

    @Test fun aFailedLookupIsNull() = runTest {
        val failing = fs(mutableListOf(), """{"error":"EIO"}""", HttpStatusCode.InternalServerError)
        assertNull(lookupMentionFiles(failing, "/w", MentionLookup.Search("x")))
        assertNull(lookupMentionFiles(failing, "/w", MentionLookup.Folder("src")))
    }
}
