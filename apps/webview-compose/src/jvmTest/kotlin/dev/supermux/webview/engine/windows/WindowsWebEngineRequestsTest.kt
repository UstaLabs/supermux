package dev.supermux.webview.engine.windows

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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

    @Test fun aDownloadIsRefusedAndTheClientTold() = runTest {
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.engine.onEvent(WinEvent.Download(rig.page.epoch, "https://a.example/f.zip", "application/zip", "f.zip"))
        assertEquals(DownloadRequest("https://a.example/f.zip", "f.zip", "application/zip", null), rig.client.asked.last())
        rig.close()
    }

    @Test fun aLoadThatIsADownloadEndsWithAnError() = runTest {
        // The lease's own navigation turned into a download: no document will come, so the load ends (the cover lifts).
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.engine.load(WebContent.Url("https://a.example/f.zip"))
        rig.starts("https://a.example/f.zip")
        rig.engine.onEvent(WinEvent.Download(rig.page.epoch, "https://a.example/f.zip", "application/zip", "f.zip"))
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
}
