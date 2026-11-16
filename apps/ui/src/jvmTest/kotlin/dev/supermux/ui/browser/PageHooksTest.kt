package dev.supermux.ui.browser

import dev.supermux.ui.platform.DeviceGrant
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.PageDevice
import dev.supermux.ui.platform.pageAccepts
import dev.supermux.ui.prefs.FakeSettingsStore
import dev.supermux.webview.DownloadFailure
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDownload
import dev.supermux.webview.WebDownloadState
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #157: a page's downloads, file inputs and permissions, answered the app's way. */
class PageHooksTest {
    /** A transfer the test drives as the engine would, recording what the app asked of it. */
    private class FakeTransfer(override val request: DownloadRequest, override val path: String, override val canPause: Boolean = true) : WebDownload {
        val asked = mutableListOf<String>()
        val flow = MutableStateFlow<WebDownloadState>(WebDownloadState.Running(0, request.contentLength))
        override val state: StateFlow<WebDownloadState> = flow
        override fun pause() { asked += "pause"; flow.value = WebDownloadState.Paused(flow.value.received, flow.value.total) }
        override fun resume() { asked += "resume"; flow.value = WebDownloadState.Running(flow.value.received, flow.value.total) }
        override fun cancel() { asked += "cancel"; flow.value = WebDownloadState.Cancelled(flow.value.received, flow.value.total) }
    }

    private class Device(
        override val downloadsDir: String? = "/tmp/dl",
        var picked: List<String>? = listOf("/tmp/a.pdf"),
        var os: Boolean = true,
    ) : PageDevice {
        val pickAsks = mutableListOf<Triple<List<String>, Boolean, String>>()
        val osAsks = mutableListOf<DeviceGrant>()
        override suspend fun pickForPage(acceptTypes: List<String>, multiple: Boolean, title: String): List<String>? {
            pickAsks += Triple(acceptTypes, multiple, title)
            return picked
        }
        override suspend fun grantOs(what: DeviceGrant): Boolean = os.also { osAsks += what }
    }

    private class Rig(scope: TestScope, val device: Device = Device()) {
        val platform = object : FakePlatform() {
            override val page: PageDevice = device
        }
        val store = FakeSettingsStore()
        val sites = SitePermissions(store) { 1000L }
        val downloads = DownloadCenter(scope.backgroundScope)
        val hooks = PageHooks(platform, downloads, sites, PageSource("tab-1", "Example"))
    }

    @Test fun aDownloadIsSavedInItsOwnFolderAndListedWithItsProgress() = runTest {
        val rig = Rig(this)
        val request = DownloadRequest("https://a.test/files/report.pdf?x=1", "report.pdf", "application/pdf", 2048)
        val target = assertNotNull(rig.hooks.download(request))
        assertTrue(target.path.startsWith("/tmp/dl/") && target.path.endsWith("/report.pdf"), target.path)
        val transfer = FakeTransfer(request, target.path)
        target.onStarted(transfer)
        runCurrent()
        val item = rig.downloads.items.value.single()
        assertEquals("report.pdf", item.name)
        assertEquals(1, rig.platform.notices.shown.size, "the start is said where the user is")
        assertEquals("application/pdf", item.mime)
        transfer.flow.value = WebDownloadState.Running(1024, 2048)
        runCurrent()
        assertEquals(DownloadState.Running(1024, 2048), rig.downloads.items.value.single().state)
        transfer.flow.value = WebDownloadState.Done(2048)
        runCurrent()
        val done = assertIs<DownloadState.Done>(rig.downloads.items.value.single().state)
        assertEquals(target.path, done.file.path)
        assertEquals(2048, done.file.size)
    }

    @Test fun theListsPauseRetryAndCancelReachTheTransfer() = runTest {
        val rig = Rig(this)
        val request = DownloadRequest("https://a.test/big.zip", "big.zip", "application/zip", null)
        val target = assertNotNull(rig.hooks.download(request))
        val transfer = FakeTransfer(request, target.path)
        target.onStarted(transfer)
        runCurrent()
        val key = rig.downloads.items.value.single().key
        rig.downloads.cancel(key)
        runCurrent()
        assertEquals(listOf("pause"), transfer.asked, "the list's Pause pauses, keeping what arrived")
        rig.downloads.retry(key)
        runCurrent()
        assertEquals(listOf("pause", "resume"), transfer.asked)
        rig.downloads.dismiss(key)
        runCurrent()
        assertEquals(listOf("pause", "resume", "cancel"), transfer.asked, "Cancel throws it away")
        assertTrue(rig.downloads.items.value.isEmpty())
    }

    @Test fun clearingAPausedDownloadThrowsItAway() = runTest {
        val rig = Rig(this)
        val request = DownloadRequest("https://a.test/big.zip", "big.zip", null, null)
        val target = assertNotNull(rig.hooks.download(request))
        val transfer = FakeTransfer(request, target.path)
        target.onStarted(transfer)
        runCurrent()
        rig.downloads.cancel(rig.downloads.items.value.single().key)
        runCurrent()
        rig.downloads.clearFinished()
        assertEquals(listOf("pause", "cancel"), transfer.asked)
    }

    @Test fun aFailureIsListedWithTheEnginesReason() = runTest {
        val rig = Rig(this)
        val request = DownloadRequest("https://a.test/gone.zip", null, null, null)
        val target = assertNotNull(rig.hooks.download(request))
        val transfer = FakeTransfer(request, target.path)
        target.onStarted(transfer)
        runCurrent()
        transfer.flow.value = WebDownloadState.Failed("HTTP 404", 0, null)
        runCurrent()
        assertEquals(DownloadState.Failed("HTTP 404"), rig.downloads.items.value.single().state)
        assertEquals("gone.zip", rig.downloads.items.value.single().name)
    }

    @Test fun aFormsDownloadThatCantBeSavedSaysSoInTheAppsWordsAndIsNotListed() = runTest {
        val rig = Rig(this)
        val request = DownloadRequest("https://a.test/export", "export.csv", "text/csv", null)
        val target = assertNotNull(rig.hooks.download(request))
        val transfer = FakeTransfer(request, target.path)
        transfer.flow.value = WebDownloadState.Failed("A form's answer can't be saved here", 0, null, DownloadFailure.FormPost)
        target.onStarted(transfer)
        runCurrent()
        val said = rig.platform.notices.shown.single()
        assertTrue(said.contains("browser"), said)
        assertTrue(rig.downloads.items.value.isEmpty(), "a Retry could never fetch it")
    }

    @Test fun noDownloadsFolderMeansThePageDoesNotDownloadThroughTheApp() = runTest {
        val rig = Rig(this, Device(downloadsDir = null))
        assertNull(rig.hooks.download(DownloadRequest("https://a.test/a.zip", "a.zip", null, null)))
    }

    @Test fun aFileNameIsNeverAPathOrEmpty() {
        assertEquals("a.zip", downloadName(DownloadRequest("https://a.test/x", "../../a.zip", null, null)))
        assertEquals("passwd", downloadName(DownloadRequest("https://a.test/x", "/etc/passwd", null, null)))
        assertEquals("report.pdf", downloadName(DownloadRequest("https://a.test/r/report.pdf?t=1#p", null, null, null)))
        assertEquals("download", downloadName(DownloadRequest("blob:https://a.test/1234", null, null, null)))
        assertEquals("download", downloadName(DownloadRequest("https://a.test/", " ", null, null)))
        assertEquals("ab.txt", downloadName(DownloadRequest("https://a.test/", "a<b>.txt", null, null)))
    }

    @Test fun aFileInputGetsThePickerWithItsTypes() = runTest {
        val rig = Rig(this)
        val files = rig.hooks.chooseFiles(FileChooserRequest(listOf("image/*", ".pdf"), allowMultiple = true))
        assertEquals(listOf("/tmp/a.pdf"), files)
        assertEquals(listOf("image/*", ".pdf"), rig.device.pickAsks.single().first)
        assertTrue(rig.device.pickAsks.single().second)
        rig.device.picked = emptyList()
        assertNull(rig.hooks.chooseFiles(FileChooserRequest(emptyList(), allowMultiple = false)), "nothing picked is a cancel")
    }

    @Test fun aSiteIsAskedOnceAndTheAnswerIsRemembered() = runTest {
        val rig = Rig(this)
        val camera = PermissionRequest("https://meet.test", setOf(PermissionKind.Camera, PermissionKind.Microphone))
        val first = async { rig.hooks.permission(camera) }
        val prompt = rig.hooks.prompt.first { it != null }!!
        assertEquals("https://meet.test", prompt.origin)
        prompt.answer(true)
        assertTrue(first.await())
        assertNull(rig.hooks.prompt.value)
        assertEquals(listOf(DeviceGrant.Camera, DeviceGrant.Microphone), rig.device.osAsks)
        // Asked again: answered from what the user said, no prompt.
        assertTrue(rig.hooks.permission(camera))
        assertNull(rig.hooks.prompt.value)
        val stored = rig.sites.decisions.first()
        assertEquals(setOf("Camera", "Microphone"), stored.map { it.kind }.toSet())
        assertTrue(stored.all { it.allow && it.origin == "https://meet.test" && it.at == 1000L })
    }

    @Test fun aBlockedSiteIsRefusedWithoutAsking() = runTest {
        val rig = Rig(this)
        val where = PermissionRequest("https://ads.test", setOf(PermissionKind.Geolocation))
        val first = async { rig.hooks.permission(where) }
        rig.hooks.prompt.first { it != null }!!.answer(false)
        assertFalse(first.await())
        assertFalse(rig.hooks.permission(where))
        assertNull(rig.hooks.prompt.value)
        assertTrue(rig.device.osAsks.isEmpty(), "the OS is never asked for a site the user blocked")
        // Forgotten in Settings: it asks again.
        rig.sites.forget("https://ads.test")
        val again = async { rig.hooks.permission(where) }
        assertNotNull(rig.hooks.prompt.first { it != null })
        rig.hooks.prompt.value!!.answer(true)
        assertTrue(again.await())
    }

    @Test fun anOsRefusalSaysWhereToTurnItOn() = runTest {
        val rig = Rig(this, Device(os = false))
        rig.sites.decide("https://meet.test", setOf(PermissionKind.Camera), allow = true)
        assertFalse(rig.hooks.permission(PermissionRequest("https://meet.test", setOf(PermissionKind.Camera))))
        assertEquals(1, rig.platform.notices.shown.size)
    }

    @Test fun whatAPageCantNameIsAskedEachTime() = runTest {
        val rig = Rig(this)
        val motion = PermissionRequest("https://game.test", setOf(PermissionKind.Other))
        val first = async { rig.hooks.permission(motion) }
        rig.hooks.prompt.first { it != null }!!.answer(true)
        assertTrue(first.await())
        assertTrue(rig.sites.decisions.first().isEmpty())
        val second = async { rig.hooks.permission(motion) }
        assertNotNull(rig.hooks.prompt.first { it != null })
        rig.hooks.prompt.value!!.answer(false)
        assertFalse(second.await())
    }

    @Test fun anInputsAcceptListFiltersFiles() {
        assertTrue(pageAccepts(emptyList(), "a.exe", "application/octet-stream"))
        assertTrue(pageAccepts(listOf("image/*"), "a.png", "image/png"))
        assertFalse(pageAccepts(listOf("image/*"), "a.pdf", "application/pdf"))
        assertTrue(pageAccepts(listOf(".PDF", "image/*"), "Report.pdf", "application/octet-stream"))
        assertTrue(pageAccepts(listOf("application/pdf"), "x", "application/pdf"))
    }

    @Test fun theQuestionNamesTheSiteAndWhatItWants() {
        assertEquals("meet.test", siteName("https://meet.test"))
        assertEquals("localhost:3000", siteName("http://localhost:3000"))
    }
}
