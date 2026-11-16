package dev.supermux.webview.engine.windows

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.LoadFailure
import dev.supermux.webview.WebDownload
import dev.supermux.webview.WebDownloadState
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsWebEngineRequestsTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun dialogsReachTheClientAndItsAnswerTheirPage() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.dialog = DialogResult.Text("typed")
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 1, 2, "name?", "x", demo.origin.url))
        assertEquals(WebDialog.Prompt("name?", "x", demo.origin.url), rig.client.asked.last())
        assertEquals(1 to "typed", rig.page.answers.getValue(1))
        rig.client.dialog = DialogResult.Dismissed
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 2, 1, "sure?", null, demo.origin.url))
        assertEquals(0 to null, rig.page.answers.getValue(2))
        rig.close()
    }

    @Test fun withNoClientADialogIsDismissedAndABeforeUnloadLeaves() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.engine.client = null
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 3, 0, "hi", null, demo.origin.url))
        rig.engine.onEvent(WinEvent.Dialog(rig.page.epoch, 4, 3, "leave?", null, demo.origin.url))
        assertEquals(0, rig.page.answers.getValue(3).first)
        assertEquals(1, rig.page.answers.getValue(4).first, "the page can't trap the user")
        rig.close()
    }

    @Test fun permissionsAskTheClientWithTheDocumentsOrigin() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.permission = true
        rig.engine.onEvent(WinEvent.Permission(rig.page.epoch, 5, 3, true, demo.origin.url))
        assertEquals(PermissionRequest(demo.origin.origin, setOf(PermissionKind.Geolocation)), rig.client.asked.last())
        assertEquals(1, rig.page.answers.getValue(5).first)
        rig.close()
    }

    @Test fun aReleaseAnswersWhatTheClientHasntWithItsDefault() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.gate = kotlinx.coroutines.CompletableDeferred()
        rig.engine.onEvent(WinEvent.Permission(rig.page.epoch, 6, 2, true, demo.origin.url))
        rig.engine.release()
        assertEquals(0, rig.page.answers.getValue(6).first, "denied")
        rig.close()
    }

    @Test fun aDownloadTheClientDropsIsCancelled() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WinEvent.Download(rig.page.epoch, 7, "https://a.example/f.zip", "application/zip", "f.zip", 2048))
        runCurrent()
        assertEquals(DownloadRequest("https://a.example/f.zip", "f.zip", "application/zip", 2048), rig.client.asked.last())
        assertEquals(0 to null, rig.page.answers.getValue(7), "WebView2 cancels it")
        rig.close()
    }

    @Test fun aDownloadTheClientPlacesIsSavedThereAndReported() = runTest {
        val calls = mutableListOf<String>()
        val previous = WinDownloads.ops
        WinDownloads.ops = object : WinDownloads.Ops {
            override fun pause(download: Long) { calls += "pause $download" }
            override fun resume(download: Long) { calls += "resume $download" }
            override fun cancel(download: Long) { calls += "cancel $download" }
        }
        try {
            val rig = WinRig(this, WebProfile.Default)
            rig.warm()
            rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
            var started: WebDownload? = null
            // Joined with `/`, as shared code joins it: WebView2 gets Windows' separators.
            rig.client.download = DownloadTarget("C:\\dl/7f/f.zip") { started = it }
            rig.engine.onEvent(WinEvent.Download(rig.page.epoch, 8, "https://a.example/f.zip", "application/zip", "f.zip", null))
            runCurrent()
            val (id, path) = rig.page.answers.getValue(8)
            assertTrue(id > 0)
            assertEquals("C:\\dl\\7f\\f.zip", path)
            val transfer = assertNotNull(started)
            assertEquals("C:\\dl\\7f\\f.zip", transfer.path)
            WinDownloads.on(WinEvent.DownloadProgress(id.toLong(), 100, 400))
            assertEquals(WebDownloadState.Running(100, 400), transfer.state.value)
            transfer.pause()
            WinDownloads.on(WinEvent.DownloadState(id.toLong(), 1, 100, null))
            assertEquals(WebDownloadState.Paused(100, 400), transfer.state.value)
            transfer.resume()
            WinDownloads.on(WinEvent.DownloadState(id.toLong(), 2, 100, "The connection was lost"))
            assertEquals(WebDownloadState.Failed("The connection was lost", 100, 400), transfer.state.value)
            transfer.resume()
            WinDownloads.on(WinEvent.DownloadState(id.toLong(), 0, 400, null))
            assertEquals(WebDownloadState.Done(400), transfer.state.value)
            assertEquals(listOf("pause $id", "resume $id", "resume $id"), calls)
            rig.close()
        } finally {
            WinDownloads.ops = previous
        }
    }

    @Test fun aFailedLoadNamesItsReasonForTheApp() {
        assertEquals(LoadFailure.CannotConnect, loadFailureOf(12))
        assertEquals(LoadFailure.CannotConnect, loadFailureOf(9))
        assertEquals(LoadFailure.ConnectionLost, loadFailureOf(10))
        assertEquals(LoadFailure.NameNotResolved, loadFailureOf(13))
        assertEquals(LoadFailure.Other, loadFailureOf(99))
    }

    @Test fun aLoadThatIsADownloadEndsWithAnError() = runTest {
        // The lease's own navigation turned into a download: no document will come, so the load ends (the cover lifts).
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.load(WebContent.Url("https://a.example/f.zip"))
        rig.starts("https://a.example/f.zip")
        rig.engine.onEvent(WinEvent.Download(rig.page.epoch, 9, "https://a.example/f.zip", "application/zip", "f.zip", null))
        assertEquals(EngineEvent.Error(WebError.BlockedByPolicy("https://a.example/f.zip")), rig.events.last { it is EngineEvent.Error })
        rig.close()
    }

    @Test fun aNewWindowAsksTheClientAndOpensHereAsTheEnginesOwnLoad() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.client.newWindow = NewWindowDecision.OpenHere
        rig.engine.onEvent(WinEvent.NewWindow(rig.page.epoch, "https://b.example/", true))
        assertEquals(NewWindowRequest("https://b.example/", true), rig.client.asked.last())
        assertEquals("https://b.example/", rig.page.navigations.last().second)
        val asked = rig.client.asked.size
        rig.starts("https://b.example/")
        assertEquals(asked, rig.client.asked.size, "not asked again at its navigation")
        rig.close()
    }

    @Test fun theFloorAsANewWindowIsNeverAsked() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        val asked = rig.client.asked.size
        rig.engine.onEvent(WinEvent.NewWindow(rig.page.epoch, Wv2Navigation.FLOOR_URL, true))
        assertEquals(asked, rig.client.asked.size)
        rig.close()
    }

    @Test fun aFileChooserAsksTheClientAndSetsItsFiles() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.client.files = listOf("file:///C:/Users/t/a.txt", "C:\\Users\\t\\b.png", "https://x/")
        rig.engine.onEvent(WinEvent.DevToolsEvent(rig.page.epoch, "Page.fileChooserOpened", "{\"frameId\":\"F\",\"mode\":\"selectMultiple\",\"backendNodeId\":42}"))
        val describe = rig.page.devtoolsRequest("DOM.describeNode")
        rig.engine.onEvent(WinEvent.Result(describe, true, "{\"node\":{\"attributes\":[\"type\",\"file\",\"accept\",\"image/png,.txt\"]}}"))
        assertEquals(FileChooserRequest(listOf("image/png", ".txt"), true), rig.client.asked.last())
        val set = rig.page.devtools.last { it.second == "DOM.setFileInputFiles" }
        assertEquals("{\"files\":[\"C:\\\\Users\\\\t\\\\a.txt\",\"C:\\\\Users\\\\t\\\\b.png\"],\"backendNodeId\":42}", set.third)
        rig.close()
    }

    /** #122: the page's verdict on a key it was given first comes from a binding only the isolated key script can call. */
    @Test fun aWarmEngineAsksForThePagesKeyVerdicts() = runTest {
        val rig = WinRig(this)
        rig.warm()
        val script = rig.page.devtools.single { it.second == "Page.addScriptToEvaluateOnNewDocument" }.third
        assertTrue("\"worldName\":\"wvKeys\"" in script, script)
        val binding = rig.page.devtools.single { it.second == "Runtime.addBinding" }.third
        assertTrue("\"executionContextName\":\"wvKeys\"" in binding, binding)
        assertTrue("subscribe Runtime.bindingCalled" in rig.page.calls)
        rig.close()
    }
}
