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
import kotlin.test.assertFalse
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

    /**
     * The page leaves 300 ms after it starts: example.com, once in the HTTP cache, can commit and paint before the page
     * has painted, and then [EngineHarness.loadAndPaint]'s wait for a paint took example.com's commit with it.
     */
    @Test fun anAllowedNavigationCommits() = EngineHarness.run {
        main { client = RecordingClient(navigation = NavigationDecision.Allow) }
        loadAndPaint(TestPages.leaving)
        seenOrAwait("example.com's commit") { it == EngineEvent.UrlChanged("https://example.com/") }
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

    /**
     * The confirm blocks the old page until it is answered: the load answers it, and the new page paints. The client's
     * coroutine asking about it is cancelled then, so the app's dialog goes with its document (review, minor).
     */
    @Test fun aDialogStillOpenWhenTheDocumentGoesGetsItsDefault() = EngineHarness.run {
        var cancelled = false
        val client = RecordingClient(
            dialog = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        main { this.client = client }
        main {
            configure(setupFor(TestPages.dialogs))
            load(TestPages.dialogs)
        }
        untilAsked(client, "the confirm") { it is WebDialog.Confirm }
        loadAndPaint(TestPages.solid("#00ffff"))
        assertEquals(1, askedOf(client).count { it is WebDialog }, "the prompt that followed got its default unasked")
        assertTrue(main { cancelled }, "the client still asks about a dialog whose document went")
    }

    /**
     * The view that asked goes (it parks the page, and its dialog went with it): what it was asked gets its default then,
     * or the page would wait on a dialog no UI shows, the adopting view's page with it (final review, as iOS does).
     * TestPages.dialogs asks a confirm, then a prompt: with no client, the prompt gets its default unasked.
     */
    @Test fun aDialogPendingWhenItsClientGoesGetsItsDefault() = EngineHarness.run {
        var cancelled = false
        val client = RecordingClient(
            dialog = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        main { this.client = client }
        main {
            configure(setupFor(TestPages.dialogs))
            load(TestPages.dialogs)
        }
        untilAsked(client, "the confirm") { it is WebDialog.Confirm }
        main { this.client = client }
        delay(300)
        assertFalse(main { cancelled }, "the same client still answers")
        main { this.client = null }
        seenOrAwait("the defaults") { it == EngineEvent.TitleChanged("confirm false prompt null") }
        assertTrue(main { cancelled }, "the client still asks about a dialog its view no longer shows")
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
