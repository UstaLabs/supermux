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
    fun compareVersions_prereleaseIdentifiersAreNumeric() {
        assertEquals(-1, compareVersions("0.12.0-alpha.2", "0.12.0-alpha.10"))
        assertEquals(1, compareVersions("0.12.0-alpha.10", "0.12.0-alpha.9"))
        assertEquals(-1, compareVersions("0.12.0-alpha.9", "0.12.0-beta.1"))
        assertEquals(-1, compareVersions("0.12.0-1", "0.12.0-alpha"))
        assertEquals(-1, compareVersions("0.12.0-alpha", "0.12.0-alpha.1"))
        assertTrue(isUpdateAvailable("0.11.36", "0.12.0-alpha.1"))
        assertTrue(isUpdateAvailable("0.12.0-alpha.10", "0.12.0"))
        assertFalse(isUpdateAvailable("0.12.0-alpha.10", "0.11.37"))
    }

    // stable 0.11.36 with an alpha train already ahead of it.
    private val twoChannelPayload = """
        {
          "schemaVersion": 1,
          "channels": {
            "stable": {
              "version": "0.11.36",
              "notesUrl": "https://example.com/notes/stable",
              "assets": { "desktop-linux": { "url": "https://example.com/stable.deb", "sha256": "s" } }
            },
            "alpha": {
              "version": "0.12.0-alpha.3",
              "notesUrl": "https://example.com/notes/alpha",
              "assets": { "desktop-linux": { "url": "https://example.com/alpha.deb", "sha256": "a" } }
            }
          }
        }
    """.trimIndent()

    @Test
    fun check_stableBuildIsNeverOfferedTheAlpha() = runTest {
        val http = mockHttp(mapOf("https://supermux.dev/versions.json" to twoChannelPayload))
        val status = ClientUpdateChecker(http).check(ClientPlatform.DESKTOP_LINUX, currentVersion = "0.11.36")
        assertFalse(status.updateAvailable)
        assertEquals("0.11.36", status.latestVersion)
        assertEquals("https://example.com/stable.deb", status.downloadUrl)
    }

    @Test
    fun check_alphaBuildFollowsTheAlphaChannel() = runTest {
        val http = mockHttp(mapOf("https://supermux.dev/versions.json" to twoChannelPayload))
        val status = ClientUpdateChecker(http).check(ClientPlatform.DESKTOP_LINUX, currentVersion = "0.12.0-alpha.1")
        assertTrue(status.updateAvailable)
        assertEquals("0.12.0-alpha.3", status.latestVersion)
        assertEquals("https://example.com/alpha.deb", status.downloadUrl)
        assertEquals("https://example.com/notes/alpha", status.notesUrl)
    }

    @Test
    fun channelFor_alphaBuildFallsBackToStableWithoutAnAlphaBlock() {
        val manifest = VersionsManifest(channels = ChannelsWrapper(stable = ChannelInfo(version = "0.11.36")))
        assertEquals("0.11.36", manifest.channelFor("0.12.0-alpha.1").version)
        assertEquals("0.11.36", manifest.channelFor("dev").version)
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

    @Test
    fun download_invokesProgressAndReturnsBytes() = runTest {
        val payload = ByteArray(8_192) { i -> (i % 251).toByte() }
        val engine = MockEngine { request ->
            assertEquals("https://example.com/file.bin", request.url.toString())
            respond(
                payload,
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                    HttpHeaders.ContentLength to listOf(payload.size.toString()),
                ),
            )
        }
        val http = HttpClient(engine)
        val ticks = mutableListOf<Pair<Long, Long?>>()
        val got = ClientUpdateChecker(http).download("https://example.com/file.bin") { received, total ->
            ticks += received to total
        }
        assertTrue(got.contentEquals(payload))
        assertTrue(ticks.isNotEmpty(), "expected at least one progress tick")
        assertEquals(payload.size.toLong(), ticks.last().first)
        // Content-Length is forwarded when the mock provides it.
        assertTrue(ticks.any { it.second == payload.size.toLong() || it.second == null })
    }

    @Test
    fun download_httpErrorThrows() = runTest {
        val engine = MockEngine {
            respond("nope", HttpStatusCode.NotFound)
        }
        val http = HttpClient(engine)
        val result = runCatching {
            ClientUpdateChecker(http).download("https://example.com/missing.apk")
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("404"))
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
