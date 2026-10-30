package dev.supermux.webview.engine.linux

import dev.supermux.webview.DialogResult
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class LinuxWebEngineRequestsTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

    private fun live(test: TestScope): EngineRig {
        val rig = EngineRig(test)
        rig.warm()
        rig.show(demo, demo.origin.url)
        return rig
    }

    @Test fun aConfirmAsksTheClientWithThePagesUrlAndAnswersWebKit() = runTest {
        val rig = live(this)
        rig.client.dialog = DialogResult.Confirmed
        rig.engine.onEvent(WpeEvent.Dialog(3, WpeNative.DIALOG_CONFIRM, "Sure?", null))
        assertContains(rig.client.asked, WebDialog.Confirm("Sure?", demo.origin.url))
        assertEquals(Triple(true, null, null), rig.view.answers[3])
        rig.close()
    }

    @Test fun aPromptsTextOrItsDefaultIsTheAnswer() = runTest {
        val rig = live(this)
        rig.client.dialog = DialogResult.Text("typed")
        rig.engine.onEvent(WpeEvent.Dialog(4, WpeNative.DIALOG_PROMPT, "Name?", "default"))
        assertEquals(Triple(true, "typed", null), rig.view.answers[4])
        rig.client.dialog = DialogResult.Confirmed
        rig.engine.onEvent(WpeEvent.Dialog(5, WpeNative.DIALOG_PROMPT, "Name?", "default"))
        assertEquals(Triple(true, "default", null), rig.view.answers[5])
        rig.client.dialog = DialogResult.Dismissed
        rig.engine.onEvent(WpeEvent.Dialog(6, WpeNative.DIALOG_PROMPT, "Name?", "default"))
        assertEquals(Triple(false, null, null), rig.view.answers[6])
        rig.close()
    }

    @Test fun withNoClientABeforeUnloadLeavesAndTheRestAreDismissed() = runTest {
        val rig = live(this)
        rig.engine.client = null
        rig.engine.onEvent(WpeEvent.Dialog(7, WpeNative.DIALOG_BEFORE_UNLOAD, "Leave?", null))
        rig.engine.onEvent(WpeEvent.Dialog(8, WpeNative.DIALOG_CONFIRM, "Sure?", null))
        assertEquals(true, rig.view.answers[7]?.first, "beforeunload leaves the page")
        assertEquals(false, rig.view.answers[8]?.first)
        rig.close()
    }

    @Test fun permissionsAskForTheDocumentsOriginAndTheirKinds() = runTest {
        val rig = live(this)
        rig.engine.onEvent(WpeEvent.Permission(9, WpeNative.PERM_CAMERA or WpeNative.PERM_MICROPHONE, demo.origin.url))
        assertContains(rig.client.asked, PermissionRequest(demo.origin.origin, setOf(PermissionKind.Camera, PermissionKind.Microphone)))
        assertEquals(false, rig.view.answers[9]?.first)
        rig.client.permission = true
        rig.engine.onEvent(WpeEvent.Permission(10, WpeNative.PERM_NOTIFICATIONS, demo.origin.url))
        assertEquals(true, rig.view.answers[10]?.first)
        rig.close()
    }

    @Test fun aFileChooserTakesLocalPathsAndOneUnlessMultiple() = runTest {
        val rig = live(this)
        rig.client.files = listOf("file:///tmp/a.txt", "/tmp/b.txt", "content://x/c")
        rig.engine.onEvent(WpeEvent.FileChooser(11, multiple = false, mimeTypes = listOf("text/plain")))
        assertContains(rig.client.asked, FileChooserRequest(listOf("text/plain"), allowMultiple = false))
        assertEquals(Triple(true, null, listOf("/tmp/a.txt")), rig.view.answers[11])
        rig.engine.onEvent(WpeEvent.FileChooser(12, multiple = true, mimeTypes = emptyList()))
        assertEquals(Triple(true, null, listOf("/tmp/a.txt", "/tmp/b.txt")), rig.view.answers[12])
        rig.client.files = null
        rig.engine.onEvent(WpeEvent.FileChooser(13, multiple = false, mimeTypes = emptyList()))
        assertEquals(Triple(false, null, null), rig.view.answers[13], "cancelled")
        rig.close()
    }

    @Test fun aNewWindowAsksTheClientAndOpensHereOnlyIfItSaysSo() = runTest {
        val rig = live(this)
        rig.engine.onEvent(WpeEvent.NewWindow("https://b.example/", gesture = true))
        assertContains(rig.client.asked, NewWindowRequest("https://b.example/", isUserGesture = true))
        assertTrue(rig.view.calls.none { it == "loadUri https://b.example/" })
        rig.client.newWindow = NewWindowDecision.OpenHere
        rig.engine.onEvent(WpeEvent.NewWindow("https://b.example/", gesture = true))
        assertContains(rig.view.calls, "loadUri https://b.example/")
        rig.close()
    }

    @Test fun privateSchemesInANewWindowAreNeverAsked() = runTest {
        val rig = live(this)
        rig.engine.onEvent(WpeEvent.NewWindow(WebKitUrls.bundleUrl(WebOrigin("other"), "x.html"), gesture = true))
        rig.engine.onEvent(WpeEvent.NewWindow(WebKitUrls.FLOOR_URL, gesture = true))
        assertTrue(rig.client.asked.none { it is NewWindowRequest }, "${rig.client.asked}")
        rig.close()
    }

    @Test fun releaseAnswersWhatIsPendingWithItsDefault() = runTest {
        val rig = live(this)
        rig.client.gate = CompletableDeferred()
        rig.engine.onEvent(WpeEvent.Dialog(14, WpeNative.DIALOG_CONFIRM, "Sure?", null))
        rig.engine.onEvent(WpeEvent.Permission(15, WpeNative.PERM_GEOLOCATION, demo.origin.url))
        assertNull(rig.view.answers[14], "waiting for the client")
        rig.engine.release()
        assertEquals(false, rig.view.answers[14]?.first)
        assertEquals(false, rig.view.answers[15]?.first)
        rig.close()
    }

    @Test fun aResetAnswersWhatIsPendingWithItsDefault() = runTest {
        val rig = live(this)
        rig.client.gate = CompletableDeferred()
        rig.engine.onEvent(WpeEvent.FileChooser(16, multiple = false, mimeTypes = emptyList()))
        rig.scope.launch { rig.engine.reset() }
        assertEquals(Triple(false, null, null), rig.view.answers[16])
        rig.close()
    }

    @Test fun chosenFilesAreLocalPaths() {
        assertEquals("/tmp/a b.txt", LinuxWebEngine.localPath("file:///tmp/a%20b.txt"))
        assertEquals("/tmp/x", LinuxWebEngine.localPath("/tmp/x"))
        assertNull(LinuxWebEngine.localPath("content://media/1"))
    }
}
