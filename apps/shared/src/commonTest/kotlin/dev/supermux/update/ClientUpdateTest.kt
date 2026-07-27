package dev.supermux.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientUpdateTest {
    @Test
    fun compareVersions_numericNotLexicographic() {
        assertEquals(1, compareVersions("0.10.0", "0.9.9"))
        assertEquals(-1, compareVersions("0.9.9", "0.10.0"))
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(-1, compareVersions("0.2.0-rc.1", "0.2.0"))
        assertEquals(-1, compareVersions("dev", "0.0.1"))
    }

    @Test
    fun isUpdateAvailable_rejectsDevAndEqual() {
        assertTrue(isUpdateAvailable("0.1.0", "0.2.0"))
        assertFalse(isUpdateAvailable("0.2.0", "0.2.0"))
        assertFalse(isUpdateAvailable("dev", "9.9.9"))
    }

    @Test
    fun check_usesClientMarketingVersionAndAsset() = runTest {
        val payload = """
            {
              "schemaVersion": 1,
              "channels": {
                "stable": {
                  "version": "1.0.0",
                  "publishedAt": "2026-07-01T00:00:00Z",
                  "notesUrl": "https://github.com/UstaLabs/supermux/releases/tag/v1.0.0",
                  "assets": {
                    "android": {
                      "url": "https://example.com/supermux-android.apk",
                      "sha256": "abc"
                    }
                  },
                  "clients": {
                    "android": { "version": "0.9.9", "versionCode": 33 }
                  }
                }
              }
            }
        """.trimIndent()
        val http = mockHttp(mapOf(
            "https://supermux.dev/versions.json" to payload,
        ))
        val status = ClientUpdateChecker(http).check(
            platform = ClientPlatform.ANDROID,
            currentVersion = "0.9.8",
            currentVersionCode = 32,
        )
        assertTrue(status.updateAvailable)
        assertEquals("0.9.9", status.latestVersion)
        assertEquals(33, status.latestVersionCode)
        assertEquals("https://example.com/supermux-android.apk", status.downloadUrl)
        assertTrue(status.canInstall)
        assertNull(status.lastError)
    }

    @Test
    fun check_versionCodeWinsOverSemver() = runTest {
        val payload = """
            {
              "schemaVersion": 1,
              "channels": {
                "stable": {
                  "version": "1.0.0",
                  "publishedAt": "2026-07-01T00:00:00Z",
                  "notesUrl": "https://example.com/notes",
                  "assets": {
                    "android": { "url": "https://example.com/a.apk", "sha256": "x" }
                  },
                  "clients": {
                    "android": { "version": "0.9.0", "versionCode": 40 }
                  }
                }
              }
            }
        """.trimIndent()
        val http = mockHttp(mapOf("https://supermux.dev/versions.json" to payload))
        // Marketing string looks older, but versionCode is newer → update available.
        val status = ClientUpdateChecker(http).check(
            platform = ClientPlatform.ANDROID,
            currentVersion = "1.0.0",
            currentVersionCode = 32,
        )
        assertTrue(status.updateAvailable)
    }

    @Test
    fun check_iosCannotInstall() = runTest {
        val payload = """
            {
              "schemaVersion": 1,
              "channels": {
                "stable": {
                  "version": "1.0.0",
                  "publishedAt": "2026-07-01T00:00:00Z",
                  "notesUrl": "https://example.com/notes",
                  "assets": {},
                  "clients": {
                    "ios": { "version": "1.4", "build": 60 }
                  }
                }
              }
            }
        """.trimIndent()
        val http = mockHttp(mapOf("https://supermux.dev/versions.json" to payload))
        val status = ClientUpdateChecker(http).check(
            platform = ClientPlatform.IOS,
            currentVersion = "1.3",
            currentBuild = 52,
        )
        assertTrue(status.updateAvailable)
        assertEquals("1.4", status.latestVersion)
        assertFalse(status.canInstall)
        assertNull(status.downloadUrl)
    }

    @Test
    fun check_fallsBackToGitHubLatest() = runTest {
        val github = """
            {
              "tag_name": "v2.0.0",
              "html_url": "https://github.com/UstaLabs/supermux/releases/tag/v2.0.0",
              "assets": [
                {
                  "name": "supermux-linux.deb",
                  "browser_download_url": "https://github.com/UstaLabs/supermux/releases/download/v2.0.0/supermux-linux.deb"
                }
              ]
            }
        """.trimIndent()
        val http = mockHttp(
            responses = mapOf(
                "https://api.github.com/repos/UstaLabs/supermux/releases/latest" to github,
            ),
            failUrls = setOf("https://supermux.dev/versions.json"),
        )
        val status = ClientUpdateChecker(http).check(
            platform = ClientPlatform.DESKTOP_LINUX,
            currentVersion = "1.0.0",
        )
        assertTrue(status.updateAvailable)
        assertEquals("2.0.0", status.latestVersion)
        assertTrue(status.canInstall)
        assertTrue(status.downloadUrl!!.endsWith("supermux-linux.deb"))
    }

    private fun mockHttp(
        responses: Map<String, String>,
        failUrls: Set<String> = emptySet(),
    ): HttpClient {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url in failUrls -> respond("boom", HttpStatusCode.InternalServerError)
                url in responses -> respond(
                    responses.getValue(url),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("not found: $url", HttpStatusCode.NotFound)
            }
        }
        return HttpClient(engine)
    }
}
