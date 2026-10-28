package dev.supermux.webview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WebClientTest {
    private val page = WebContent.Url("https://a.test/")
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))
    private val plugin = WebContent.Bundle(
        WebOrigin("plug"),
        object : WebResourceProvider {
            override suspend fun load(path: String): WebResource? = null
        },
    )

    private fun decide(url: String, content: WebContent, mainFrame: Boolean = true, gesture: Boolean = true) =
        defaultNavigationDecision(NavigationRequest(url, isMainFrame = mainFrame, isUserGesture = gesture), content)

    @Test fun aWebPageMayNavigateAnywhereOnTheWeb() {
        assertEquals(NavigationDecision.Allow, decide("https://other.test/x", page))
        assertEquals(NavigationDecision.Allow, decide("http://plain.test/", page))
    }

    @Test fun aWebPageMayNotNavigateIntoALocalOrigin() {
        assertEquals(NavigationDecision.Block, decide("https://c0123.invalid/", page))
        assertEquals(NavigationDecision.Block, decide("https:\\\\demo.invalid\\x", page))
    }

    @Test fun localContentStaysOnItsOwnOrigin() {
        assertEquals(NavigationDecision.Allow, decide("https://demo.invalid/page2", demo))
        assertEquals(NavigationDecision.Allow, decide("https://plug.invalid/settings.html", plugin))
        assertEquals(NavigationDecision.OpenExternally, decide("https://evil.test/", demo))
        assertEquals(NavigationDecision.OpenExternally, decide("https://demo.invalid/", plugin))
    }

    @Test fun localContentLeavesOnlyOnAUserGesture() {
        assertEquals(NavigationDecision.Block, decide("https://evil.test/", demo, gesture = false))
    }

    @Test fun lookalikeOriginsAreNotTheDemosOwn() {
        assertEquals(NavigationDecision.OpenExternally, decide("https://demo.invalid.evil.test/", demo))
        assertEquals(NavigationDecision.OpenExternally, decide("https://demo.invalid@evil.test/", demo))
    }

    @Test fun localContentOpensItsOwnBlobsButNotDataOrForeignBlobs() {
        assertEquals(NavigationDecision.Allow, decide("blob:https://demo.invalid/1234", demo))
        assertEquals(NavigationDecision.Block, decide("blob:https://evil.test/1234", demo))
        assertEquals(NavigationDecision.Block, decide("data:text/html,<p>x", demo))
    }

    @Test fun onlyAboutBlankAndSrcdocAreAllowed() {
        assertEquals(NavigationDecision.Allow, decide("about:blank", demo))
        assertEquals(NavigationDecision.Allow, decide("ABOUT:SRCDOC", demo))
        assertEquals(NavigationDecision.Block, decide("about:config", page))
    }

    @Test fun subframesAreTheDocumentsOwnBusiness() {
        assertEquals(NavigationDecision.Allow, decide("https://ads.test/", demo, mainFrame = false))
    }

    @Test fun javascriptAndSchemeLessUrlsAreBlockedHoweverTheyAreWritten() {
        listOf("javascript:alert(1)", "  JavaScript:alert(1)", "java\nscript:alert(1)", "\tjavascript:x", "//evil.test", "/x", "")
            .forEach { assertEquals(NavigationDecision.Block, decide(it, page), it) }
    }

    @Test fun onlyMailTelAndSmsLeaveTheAppAndOnlyOnAGesture() {
        listOf("mailto:a@b.test", "tel:+90555", "SMS:+90555").forEach { assertEquals(NavigationDecision.OpenExternally, decide(it, page), it) }
        assertEquals(NavigationDecision.Block, decide("mailto:a@b.test", page, gesture = false))
        listOf("file:///etc/passwd", "intent://x#Intent;end", "ms-msdt:/id", "vscode://x", "smb://host/share", "DATA:text/html,x")
            .forEach { assertEquals(NavigationDecision.Block, decide(it, page), it) }
    }

    @Test fun theDefaultClientAnswersEverythingSafely() = runTest {
        val client = WebClient.Default
        assertEquals(DialogResult.Dismissed, client.onDialog(WebDialog.Alert("hi")))
        assertEquals(DialogResult.Dismissed, client.onDialog(WebDialog.Confirm("sure?")))
        assertEquals(DialogResult.Dismissed, client.onDialog(WebDialog.Prompt("name?", "x")))
        assertEquals(DialogResult.Confirmed, client.onDialog(WebDialog.BeforeUnload("leave?")))
        assertNull(client.onFileChooser(FileChooserRequest(listOf("image/*"), allowMultiple = false)))
        assertFalse(client.onPermission(PermissionRequest("https://a.test", setOf(PermissionKind.Camera))))
        assertFalse(client.onDownload(DownloadRequest("https://a.test/f.zip", "f.zip", null, null)))
        assertEquals(NewWindowDecision.Block, client.onNewWindow(NewWindowRequest("https://a.test/", isUserGesture = true)))
        assertEquals(
            NavigationDecision.OpenExternally,
            client.onNavigation(NavigationRequest("https://evil.test/", isMainFrame = true, isUserGesture = true), demo),
        )
    }
}
