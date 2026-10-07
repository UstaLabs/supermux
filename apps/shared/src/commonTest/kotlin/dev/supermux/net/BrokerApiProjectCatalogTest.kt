package dev.supermux.net

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PROJECT_JSON =
    """{"id":"p1","name":"Supermux","sort_order":0,"created_at":"t","locations":[{"id":"l1","path":"/a"}]}"""

class BrokerApiProjectCatalogTest {
    private fun captured(
        sink: MutableList<HttpRequestData>,
        body: String = PROJECT_JSON,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    private fun HttpRequestData.text(): String = when (val b = body) {
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        else -> ""
    }

    @Test fun listProjectCatalog_gets_and_unwraps() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(reqs, body = """{"projects":[$PROJECT_JSON]}""")
        val list = api.listProjectCatalog()
        assertEquals(listOf("p1"), list.map { it.id })
        assertEquals(HttpMethod.Get, reqs.single().method)
        assertEquals("http://h/project-catalog", reqs.single().url.toString())
    }

    @Test fun createProject_posts_name() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val p = captured(reqs, status = HttpStatusCode.Created).createProject("Supermux")
        assertEquals("p1", p.id)
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/project-catalog", r.url.toString())
        assertEquals("""{"name":"Supermux"}""", r.text())
    }

    @Test fun renameProject_patches_by_id() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs).renameProject("p1", "New")
        val r = reqs.single()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("http://h/project-catalog/p1", r.url.toString())
        assertEquals("""{"name":"New"}""", r.text())
    }

    @Test fun reorderProjects_patches_ordered_ids() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs, body = """{"ok":true}""").reorderProjects(listOf("b", "a"))
        val r = reqs.single()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("http://h/project-catalog/reorder", r.url.toString())
        assertEquals("""{"orderedIds":["b","a"]}""", r.text())
    }

    @Test fun addProjectLocation_posts_path() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs).addProjectLocation("p1", "/a")
        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/project-catalog/p1/locations", r.url.toString())
        assertEquals("""{"path":"/a"}""", r.text())
    }

    @Test fun addProjectLocation_409_throws_conflict_with_owner() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(reqs, body = """{"error":"location_taken","projectId":"p9"}""", status = HttpStatusCode.Conflict)
        val e = assertFailsWith<ProjectLocationConflict> { api.addProjectLocation("p1", "/a") }
        assertEquals("p9", e.projectId)
    }

    @Test fun moveProjectLocation_patches_target() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs).moveProjectLocation("l1", "p2")
        val r = reqs.single()
        assertEquals(HttpMethod.Patch, r.method)
        assertEquals("http://h/project-catalog/locations/l1", r.url.toString())
        assertEquals("""{"projectId":"p2"}""", r.text())
    }

    @Test fun setProjectImage_puts_raw_bytes_with_mime() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs).setProjectImage("p1", byteArrayOf(1, 2, 3), "image/png")
        val r = reqs.single()
        assertEquals(HttpMethod.Put, r.method)
        assertEquals("http://h/project-catalog/p1/image", r.url.toString())
        val body = r.body as OutgoingContent.ByteArrayContent
        assertEquals(listOf<Byte>(1, 2, 3), body.bytes().toList())
        assertTrue(body.contentType.toString().startsWith("image/png"))
    }

    @Test fun clearProjectImage_deletes() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        captured(reqs).clearProjectImage("p1")
        val r = reqs.single()
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("http://h/project-catalog/p1/image", r.url.toString())
    }

    @Test fun projectImageUrl_carries_cache_busting_version() {
        val api = captured(mutableListOf())
        assertEquals("http://h/project-catalog/p1/image?v=abc.png", api.projectImageUrl("p1", "abc.png"))
    }
}
