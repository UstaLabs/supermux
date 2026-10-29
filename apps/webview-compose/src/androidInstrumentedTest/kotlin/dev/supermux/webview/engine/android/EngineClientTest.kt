package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A client that records what the engine asked it, and answers as told. Main thread only, as engines call it. */
internal class RecordingClient(
    var navigation: NavigationDecision = NavigationDecision.Block,
    var dialog: suspend (WebDialog) -> DialogResult = { DialogResult.Dismissed },
    var permission: Boolean = false,
) : EngineClient {
    val asked = mutableListOf<Any>()

    override fun onNavigation(request: NavigationRequest): NavigationDecision {
        asked += request
        return navigation
    }

    override suspend fun onDialog(dialog: WebDialog): DialogResult {
        asked += dialog
        return this.dialog(dialog)
    }

    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? {
        asked += request
        return null
    }

    override suspend fun onPermission(request: PermissionRequest): Boolean {
        asked += request
        return permission
    }

    override suspend fun onDownload(request: DownloadRequest): Boolean {
        asked += request
        return false
    }

    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision {
        asked += request
        return NewWindowDecision.Block
    }
}

/** The page's requests (Task 14), on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineClientTest {
    private suspend fun EngineHarness.askedOf(client: RecordingClient): List<Any> = main { client.asked.toList() }

    /** Waits until [client] was asked something matching [predicate]. */
    private suspend fun EngineHarness.untilAsked(client: RecordingClient, what: String, predicate: (Any) -> Boolean) {
        withTimeout(15_000) {
            while (askedOf(client).none(predicate)) delay(50)
        }
    }

    @Test fun aNavigationThePageStartsIsTheClientsToDecide() = EngineHarness.run {
        val client = RecordingClient(navigation = NavigationDecision.Block)
        main { this.client = client }
        loadAndPaint(TestPages.leaving)
        untilAsked(client, "the navigation") { (it as? NavigationRequest)?.url == "https://example.com/" }
        val asked = askedOf(client).filterIsInstance<NavigationRequest>().first()
        assertTrue(asked.isMainFrame)
        expectNone("a blocked navigation's commit", millis = 1_500) { it == EngineEvent.UrlChanged("https://example.com/") }
    }

    @Test fun anAllowedNavigationCommits() = EngineHarness.run {
        main { client = RecordingClient(navigation = NavigationDecision.Allow) }
        loadAndPaint(TestPages.leaving)
        await("example.com's commit") { it == EngineEvent.UrlChanged("https://example.com/") }
    }

    @Test fun dialogsAreTheClientsToAnswer() = EngineHarness.run {
        main {
            client = RecordingClient(
                dialog = { dialog ->
                    when (dialog) {
                        is WebDialog.Confirm -> DialogResult.Confirmed
                        is WebDialog.Prompt -> DialogResult.Text("hey")
                        else -> DialogResult.Dismissed
                    }
                },
            )
        }
        loadAndPaint(TestPages.dialogs)
        seenOrAwait("the answers") { it == EngineEvent.TitleChanged("confirm true prompt hey") }
    }

    @Test fun withoutAClientEveryRequestGetsItsSafeDefault() = EngineHarness.run {
        loadAndPaint(TestPages.dialogs)
        seenOrAwait("the defaults") { it == EngineEvent.TitleChanged("confirm false prompt null") }
    }

    @Test fun aDialogStillOpenWhenTheDocumentGoesGetsItsDefault() = EngineHarness.run {
        val client = RecordingClient(dialog = { awaitCancellation() })
        main { this.client = client }
        main {
            configure(setupFor(TestPages.dialogs))
            load(TestPages.dialogs)
        }
        untilAsked(client, "the confirm") { it is WebDialog.Confirm }
        // The confirm blocks the old page until it is answered: the load answers it, and the new page paints.
        loadAndPaint(TestPages.solid("#00ffff"))
        assertEquals(1, askedOf(client).count { it is WebDialog }, "the prompt that followed got its default unasked")
    }

    @Test fun theCameraIsDeniedUnlessTheClientGrantsIt() = EngineHarness.run {
        val client = RecordingClient(permission = false)
        main { this.client = client }
        loadAndPaint(TestPages.camera)
        seenOrAwait("the refusal") { it == EngineEvent.TitleChanged("camera NotAllowedError") }
        val asked = askedOf(client).filterIsInstance<PermissionRequest>().single()
        assertEquals(PermissionRequest(TestPages.camera.origin.origin, setOf(PermissionKind.Camera)), asked)
    }
}
