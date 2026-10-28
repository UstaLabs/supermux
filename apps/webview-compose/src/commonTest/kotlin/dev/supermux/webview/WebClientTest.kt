package dev.supermux.webview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WebClientTest {
    private fun nav(url: String, mainFrame: Boolean = true) =
        NavigationRequest(url, isMainFrame = mainFrame, isUserGesture = true)

    private val page = WebContent.Url("https://a.test/")
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))

    @Test fun aUrlPageMayNavigateAnywhereOnTheWeb() {
        assertEquals(NavigationDecision.Allow, defaultNavigationDecision(nav("https://other.test/x"), page))
        assertEquals(NavigationDecision.Allow, defaultNavigationDecision(nav("http://plain.test/"), page))
    }

    @Test fun aDemoStaysOnItsOwnOrigin() {
        assertEquals(NavigationDecision.Allow, defaultNavigationDecision(nav("https://demo.webview.invalid/page2"), demo))
        assertEquals(NavigationDecision.OpenExternally, defaultNavigationDecision(nav("https://evil.test/"), demo))
    }

    @Test fun aLookalikeOriginIsNotTheDemosOwn() {
        assertEquals(
            NavigationDecision.OpenExternally,
            defaultNavigationDecision(nav("https://demo.webview.invalid.evil.test/"), demo),
        )
    }

    @Test fun subframesAreTheDocumentsOwnBusiness() {
        assertEquals(NavigationDecision.Allow, defaultNavigationDecision(nav("https://ads.test/", mainFrame = false), demo))
    }

    @Test fun nonWebSchemesLeaveTheAppAndJavascriptUrlsAreBlocked() {
        assertEquals(NavigationDecision.OpenExternally, defaultNavigationDecision(nav("mailto:a@b.test"), page))
        assertEquals(NavigationDecision.OpenExternally, defaultNavigationDecision(nav("tel:+90555"), page))
        assertEquals(NavigationDecision.Block, defaultNavigationDecision(nav("javascript:alert(1)"), page))
        assertEquals(NavigationDecision.Allow, defaultNavigationDecision(nav("about:blank"), demo))
    }

    @Test fun theDefaultClientAnswersEverythingSafely() = runTest {
        val client = WebClient.Default
        assertEquals(DialogResult.Dismissed, client.onDialog(WebDialog.Confirm("sure?")))
        assertNull(client.onFileChooser(FileChooserRequest(listOf("image/*"), allowMultiple = false)))
        assertFalse(client.onPermission(PermissionRequest("https://a.test", setOf(PermissionKind.Camera))))
        assertFalse(client.onDownload(DownloadRequest("https://a.test/f.zip", "f.zip", null, null)))
        assertEquals(NavigationDecision.Block, client.onNewWindow(NewWindowRequest("https://a.test/", isUserGesture = true)))
        assertEquals(NavigationDecision.OpenExternally, client.onNavigation(nav("https://evil.test/"), demo))
    }

    @Test fun htmlDefaultsToAnEphemeralProfileUnlessSettingsNameOne() {
        assertEquals(WebProfile.Ephemeral, WebSettings.Default.profileFor(demo))
        assertEquals(WebProfile.Default, WebSettings.Default.profileFor(page))
        assertEquals(WebProfile.Named("x"), WebSettings(profile = WebProfile.Named("x")).profileFor(demo))
    }
}
