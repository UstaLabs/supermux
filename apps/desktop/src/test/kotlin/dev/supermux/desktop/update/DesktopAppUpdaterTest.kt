package dev.supermux.desktop.update

import dev.supermux.ui.platform.DownloadedInstaller
import dev.supermux.ui.platform.UpdatePhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The REAL [DesktopAppUpdater] over a mock transport (cluster G1 review): the phases every screen
 * derives `installing`/`loading` from must SETTLE, and a live download must own the phase against
 * the check the page and the banner both fire on open.
 *
 * The OS hand-off itself ([DesktopUpdateSource.openInstaller]) is deliberately not driven — launching a real
 * `xdg-open` from a unit test is not a test — so the install case exercises the FAILURE path, which
 * is the one that must also leave `Installing`.
 */
class DesktopAppUpdaterTest {

    private val installer = ByteArray(2048) { it.toByte() }

    /** Held open by the tests that need to observe a download mid-flight. */
    private var installerGate: CompletableDeferred<Unit>? = null

    /** The remembered "download updates in the background" choice; null: never chosen (the default, on). */
    private var savedBackground: Boolean? = null
    private var installerRequests = 0
    /** The version an install was started for, remembered across the restart. */
    private var installing: String? = null
    /** Installers handed to the OS (the hand-off itself is never run from a test). */
    private val handedOff = mutableListOf<File>()

    private fun updater(restarts: Boolean = true): DesktopAppUpdater {
        val engine = MockEngine { request ->
            if (request.url.toString().contains("installer.bin")) {
                installerRequests++
                installerGate?.await()
                respond(
                    installer,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/octet-stream"),
                        HttpHeaders.ContentLength to listOf(installer.size.toString()),
                    ),
                )
            } else {
                respond(
                    VERSIONS,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )
            }
        }
        return DesktopAppUpdater(
            HttpClient(engine), currentVersion = "1.0.0",
            loadBackground = { savedBackground ?: true }, saveBackground = { savedBackground = it }, restartsToUpdate = restarts,
            loadInstalling = { installing }, saveInstalling = { installing = it }, openInstaller = { require(it.isFile) { "no installer at $it" }; handedOff += it; false },
        )
    }

    // ── #113: download updates in the background, then "Restart to update" ──

    @Test
    fun `background updates are on until the user turns them off, and the choice is remembered`() = runTest {
        val updater = updater()
        assertTrue(updater.status.value.background)
        updater.setBackgroundUpdates(false)
        assertFalse(updater.status.value.background)
        assertEquals(false, savedBackground)
        assertFalse(updater().status.value.background, "a new launch reads the choice back")
    }

    @Test
    fun `a background prepare downloads a newer release quietly and publishes it ready`() = runTest {
        val updater = updater()
        updater.prepareInBackground()
        val ready = assertNotNull(updater.status.value.ready)
        assertTrue(File(ready.location).isFile)
        assertEquals(UpdatePhase.Available, updater.status.value.phase)
        assertFalse(updater.status.value.busy)
        File(ready.location).delete()
    }

    @Test
    fun `with background updates off a prepare downloads nothing`() = runTest {
        savedBackground = false
        val updater = updater()
        updater.prepareInBackground()
        assertNull(updater.status.value.ready)
        assertEquals(0, installerRequests)
    }

    @Test
    fun `a ready update is not downloaded again`() = runTest {
        val updater = updater()
        updater.prepareInBackground()
        updater.prepareInBackground()
        assertEquals(1, installerRequests)
        updater.status.value.ready?.let { File(it.location).delete() }
    }

    @Test
    fun `an install remembers its version, and the updated app says so once`() = runTest {
        val updater = updater()
        updater.prepareInBackground()
        assertNull(updater.install(assertNotNull(updater.status.value.ready)))
        assertEquals(1, handedOff.size)
        assertEquals("9.9.9", installing)
        val reopened = DesktopAppUpdater(
            HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), currentVersion = "9.9.9",
            loadBackground = { true }, saveBackground = {}, loadInstalling = { installing }, saveInstalling = { installing = it },
        )
        assertEquals("9.9.9", reopened.justUpdatedTo)
        reopened.acknowledgeUpdated()
        assertNull(installing)
        assertNull(reopened.justUpdatedTo)
        updater.status.value.ready?.let { File(it.location).delete() }
    }

    @Test
    fun `a hand-off that failed remembers nothing`() = runTest {
        val updater = DesktopAppUpdater(
            HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), currentVersion = "1.0.0",
            loadBackground = { true }, saveBackground = {}, loadInstalling = { installing }, saveInstalling = { installing = it },
            openInstaller = { error("no installer") },
        )
        assertNotNull(updater.install(DownloadedInstaller("/tmp/x.msi", "msi")))
        assertNull(installing)
    }

    @Test
    fun `an app that did not reach the version it installed says nothing`() {
        installing = "9.9.9"
        assertNull(updater().justUpdatedTo, "still 1.0.0: the install didn't happen")
    }

    @Test
    fun `only an installer that restarts the app by itself offers Restart to update`() {
        assertTrue(updater(restarts = true).restartsToUpdate)
        assertFalse(updater(restarts = false).restartsToUpdate)
    }

    @Test
    fun `a check publishes the release it found`() = runTest {
        val updater = updater()
        val status = updater.check()
        assertEquals(UpdatePhase.Available, status.phase)
        assertEquals("9.9.9", status.release?.latestVersion)
        assertFalse(status.busy)
    }

    @Test
    fun `a finished download settles instead of stranding Downloading`() = runTest {
        val updater = updater()
        updater.check()
        val ticks = mutableListOf<Long>()
        val file = updater.download { received, _ -> ticks.add(received) }
        assertNotNull(file)
        assertTrue(ticks.isNotEmpty(), "expected progress")
        assertEquals(installer.size.toLong(), updater.status.value.bytesReceived)
        // NOT Downloading: every CTA derives `installing` from `busy` (the G1 review's MAJOR).
        assertEquals(UpdatePhase.Available, updater.status.value.phase)
        assertFalse(updater.status.value.busy)
        File(file.location).delete()
    }

    @Test
    fun `a cancelled download does not strand the phase`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        installerGate = gate
        val updater = updater()
        updater.check()
        val job = launch(Dispatchers.Default) { updater.download { _, _ -> } }
        awaitPhase(updater, UpdatePhase.Downloading)
        // Navigating away cancels the caller's scope mid-transfer.
        job.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(UpdatePhase.Available, updater.status.value.phase)
        assertFalse(updater.status.value.busy)
    }

    @Test
    fun `check does not clobber a live download`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        installerGate = gate
        val updater = updater()
        updater.check()
        val job = launch(Dispatchers.Default) { updater.download { _, _ -> } }
        awaitPhase(updater, UpdatePhase.Downloading)
        // The page and the banner BOTH check on open (the G1 review's second MAJOR).
        val seen = updater.check()
        assertEquals(UpdatePhase.Downloading, seen.phase)
        gate.complete(Unit)
        job.join()
        assertEquals(UpdatePhase.Available, updater.status.value.phase)
    }

    /** Spin (bounded) until the updater reaches [phase] — the download runs on a real dispatcher. */
    private suspend fun awaitPhase(updater: DesktopAppUpdater, phase: UpdatePhase) {
        withTimeout(10_000) {
            while (updater.status.value.phase != phase) delay(5)
        }
    }

    @Test
    fun `a failed install reports the reason and does not stay Installing`() = runTest {
        val updater = updater()
        updater.check()
        // A path that cannot be opened — the OS hand-off throws and the phase must move on.
        val err = updater.install(DownloadedInstaller("/nonexistent/supermux/nope.deb", "deb"))
        assertNotNull(err)
        assertEquals(UpdatePhase.Failed, updater.status.value.phase)
        assertEquals(err, updater.status.value.error)
    }

    @Test
    fun `nothing downloads before a check found a release`() = runTest {
        val updater = updater()
        assertNull(updater.download { _, _ -> })
        assertEquals(UpdatePhase.Idle, updater.status.value.phase)
    }

    private companion object {
        val VERSIONS = """
            {
              "schemaVersion": 1,
              "channels": {
                "stable": {
                  "version": "9.9.9",
                  "publishedAt": "2026-07-01T00:00:00Z",
                  "notesUrl": "https://example.com/notes",
                  "assets": {
                    "desktop-linux": { "url": "https://example.com/installer.bin", "sha256": "x" },
                    "desktop-windows": { "url": "https://example.com/installer.bin", "sha256": "x" },
                    "compose-desktop-macos": { "url": "https://example.com/installer.bin", "sha256": "x" }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
