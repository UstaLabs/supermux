package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Git is required for hosting agents": `GET /host`'s requirements and `POST /system/install-git`. */
class BrokerApiHostRequirementsTest {

    private val seen = mutableListOf<Pair<HttpMethod, String>>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK): BrokerApi {
        val engine = MockEngine { req ->
            seen += req.method to req.url.encodedPath
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    @Test fun host_parses_the_git_requirement() = runTest {
        val host = api(
            """{"hostId":"h1","name":"Mac","protocolVersion":1,"gitAvailable":false,
               "requirements":{"git":{"ok":false,"install":"xcode-select","hint":"Install Apple's Command Line Tools"}}}""",
        ).getHost()
        val git = host.requirements!!.git
        assertFalse(git.ok)
        assertEquals(GitRequirement.INSTALL_XCODE_SELECT, git.install)
        assertTrue(git.installable)
        assertTrue(host.requirements!!.gitMissing)
    }

    @Test fun an_older_broker_has_no_requirements() = runTest {
        assertNull(api("""{"hostId":"h1","name":"n","protocolVersion":1}""").getHost().requirements)
    }

    @Test fun manual_is_not_installable() {
        assertFalse(GitRequirement(ok = false, install = "manual", hint = "apt").installable)
    }

    @Test fun install_git_posts_and_reads_ok() = runTest {
        val r = api("""{"ok":true}""").installGit()
        assertTrue(r.ok)
        assertEquals(HttpMethod.Post to "/system/install-git", seen.single())
    }

    @Test fun install_git_on_a_manual_host_returns_the_hint_instead_of_throwing() = runTest {
        val r = api("""{"error":"manual","hint":"Install git with your package manager"}""", HttpStatusCode.BadRequest).installGit()
        assertFalse(r.ok)
        assertEquals("manual", r.error)
        assertEquals("Install git with your package manager", r.hint)
    }

    @Test fun install_git_with_an_empty_error_body_is_still_a_failure() = runTest {
        val r = api("", HttpStatusCode.InternalServerError).installGit()
        assertFalse(r.ok)
        assertEquals("HTTP 500", r.error)
    }
}
