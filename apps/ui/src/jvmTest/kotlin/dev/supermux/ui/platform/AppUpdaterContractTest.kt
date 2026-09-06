package dev.supermux.ui.platform

import dev.supermux.update.ClientUpdateStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [AppUpdater] seam's contract (cluster G1), driven against the reference [FakeAppUpdater].
 *
 * `DesktopAppUpdater` and `AndroidAppUpdater` publish the same phases from the same events — these
 * cases are what a shared update screen (cluster G5) may rely on, and the pure derivations
 * ([UpdateStatus.busy], [UpdateStatus.percent]) are asserted directly.
 */
class AppUpdaterContractTest {
    private fun release(
        latest: String? = "1.1.0",
        available: Boolean = true,
        download: String? = "https://example.test/app.apk",
        notes: String? = "https://example.test/notes",
    ) = ClientUpdateStatus(
        currentVersion = "1.0.0",
        latestVersion = latest,
        updateAvailable = available,
        notesUrl = notes,
        downloadUrl = download,
        canInstall = download != null,
    )

    @Test
    fun `a fresh updater is idle with no release`() {
        val updater = FakeAppUpdater()
        assertEquals(UpdatePhase.Idle, updater.status.value.phase)
        assertNull(updater.status.value.release)
        assertFalse(updater.status.value.busy)
    }

    @Test
    fun `a check that finds nothing newer settles on up to date`() = runTest {
        val updater = FakeAppUpdater(release = release(available = false))
        val status = updater.check()
        assertEquals(UpdatePhase.UpToDate, status.phase)
        assertEquals(status, updater.status.value)
        assertNotNull(status.release)
    }

    @Test
    fun `a check that finds a release settles on available and carries it`() = runTest {
        val updater = FakeAppUpdater(release = release())
        val status = updater.check()
        assertEquals(UpdatePhase.Available, status.phase)
        assertEquals("1.1.0", status.release?.latestVersion)
        assertNull(status.error)
    }

    @Test
    fun `a check that cannot produce a status at all fails with no release`() = runTest {
        val updater = FakeAppUpdater(release = null)
        val status = updater.check()
        assertEquals(UpdatePhase.Failed, status.phase)
        assertNull(status.release)
        assertEquals("Couldn't check for updates.", status.error)
    }

    @Test
    fun `download reports progress and hands back an installer`() = runTest {
        val updater = FakeAppUpdater(release = release())
        updater.check()
        val seen = mutableListOf<Pair<Long, Long?>>()
        val installer = updater.download { received, total -> seen.add(received to total) }
        assertNotNull(installer)
        assertEquals(listOf<Pair<Long, Long?>>(50L to 100L, 100L to 100L), seen)
        assertEquals(100, updater.status.value.percent)
    }

    @Test
    fun `download is busy while it runs and publishes each tick`() = runTest {
        val updater = FakeAppUpdater(release = release())
        updater.check()
        val phases = mutableListOf<UpdatePhase>()
        updater.download { _, _ -> phases.add(updater.status.value.phase) }
        assertEquals(listOf(UpdatePhase.Downloading, UpdatePhase.Downloading), phases)
        assertTrue(UpdateStatus(phase = UpdatePhase.Downloading).busy)
    }

    @Test
    fun `nothing downloads before a check has found a release`() = runTest {
        val updater = FakeAppUpdater(release = release())
        assertNull(updater.download { _, _ -> })
        assertEquals(UpdatePhase.Idle, updater.status.value.phase)
    }

    @Test
    fun `a refused install fails up front and asks for the permission`() = runTest {
        val updater = FakeAppUpdater(release = release(), refuseInstall = true)
        updater.check()
        assertNull(updater.download { _, _ -> })
        val status = updater.status.value
        assertEquals(UpdatePhase.Failed, status.phase)
        assertTrue(status.needsInstallPermission)
        assertEquals("Allow installing apps from this source, then try again.", status.error)
    }

    @Test
    fun `a failed download carries the platform's own message`() = runTest {
        val updater = FakeAppUpdater(release = release(), downloadError = "Connection reset")
        updater.check()
        assertNull(updater.download { _, _ -> })
        assertEquals(UpdatePhase.Failed, updater.status.value.phase)
        assertEquals("Connection reset", updater.status.value.error)
        assertFalse(updater.status.value.needsInstallPermission)
    }

    @Test
    fun `install moves to installing and returns null on success`() = runTest {
        val updater = FakeAppUpdater(release = release())
        updater.check()
        val installer = updater.download { _, _ -> }
        assertNotNull(installer)
        assertNull(updater.install(installer))
        assertEquals(UpdatePhase.Installing, updater.status.value.phase)
    }

    @Test
    fun `a failed install returns and publishes the same text`() = runTest {
        val updater = FakeAppUpdater(release = release(), installError = "Could not open installer")
        updater.check()
        val installer = updater.download { _, _ -> }
        assertNotNull(installer)
        assertEquals("Could not open installer", updater.install(installer))
        assertEquals(UpdatePhase.Failed, updater.status.value.phase)
        assertEquals("Could not open installer", updater.status.value.error)
    }

    @Test
    fun `a dismissal sticks across the next check of the same version`() = runTest {
        val updater = FakeAppUpdater(release = release())
        updater.check()
        assertFalse(updater.status.value.dismissed)
        updater.dismiss()
        assertTrue(updater.status.value.dismissed)
        assertTrue(updater.check().dismissed)
    }

    @Test
    fun `a dismissal does not carry to a newer version`() = runTest {
        val updater = FakeAppUpdater(release = release())
        updater.check()
        updater.dismiss()
        updater.release = release(latest = "1.2.0")
        assertFalse(updater.check().dismissed)
    }

    @Test
    fun `release notes only open when the release names them`() = runTest {
        val updater = FakeAppUpdater(release = release(notes = null))
        updater.check()
        updater.openReleaseNotes()
        assertEquals(0, updater.notesOpened)
        updater.release = release()
        updater.check()
        updater.openReleaseNotes()
        assertEquals(1, updater.notesOpened)
    }

    @Test
    fun `percent is null while the length is unknown and clamps once it is`() {
        assertNull(UpdateStatus(bytesReceived = 10L, contentLength = null).percent)
        assertNull(UpdateStatus(bytesReceived = 10L, contentLength = 0L).percent)
        assertEquals(50, UpdateStatus(bytesReceived = 5L, contentLength = 10L).percent)
        assertEquals(100, UpdateStatus(bytesReceived = 20L, contentLength = 10L).percent)
    }

    @Test
    fun `the no-op updater reports up to date and refuses to download`() = runTest {
        assertEquals(UpdatePhase.UpToDate, NoAppUpdater.status.value.phase)
        assertEquals(UpdatePhase.UpToDate, NoAppUpdater.check().phase)
        assertNull(NoAppUpdater.download { _, _ -> })
        assertEquals(
            "Not supported",
            NoAppUpdater.install(DownloadedInstaller("/tmp/x", "deb")),
        )
    }
}
