package dev.supermux.ui.browser

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.browser.DisplayAddress
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.webview.FindResult
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BrowserBarsTest {
    @Test fun aPreviewShowsAsItsPortAndTheSchemeNever() {
        assertEquals("port 5173/login", DisplayAddress.Preview(5173, "/login").text())
        assertEquals("localhost:3000", DisplayAddress.Local(3000, "/").text())
        assertEquals("vitejs.dev/guide/", DisplayAddress.Web("vitejs.dev", "/guide/").text())
    }

    @Test fun findSaysWhatTheEngineFound() {
        assertEquals("2 of 5", findText("x", FindResult(found = true, count = 5, current = 2)))
        assertEquals("No matches", findText("x", FindResult(found = false)))
        assertEquals("Find isn't available here yet", findText("x", null))
        assertEquals("", findText("", null))
    }

    @Test fun theAddressShowsThePortAndEditsTheWholeUrl() = runComposeUiTest {
        var submitted: String? = null
        setPlatformContent {
            BrowserToolbar(
                shown = "port 5173/login", full = "https://relay.test/p/p-5173/login", canGoBack = false, canGoForward = false,
                loading = false, zoomPercent = 100, address = remember { FocusRequester() }, onBack = {}, onForward = {}, onReload = {},
                onSubmit = { submitted = it }, onOpenExternally = {}, onFind = {}, onZoom = {}, onInspect = {},
            )
        }
        onNodeWithTag("browser-address").assertTextEquals("port 5173/login")
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").assertTextEquals("https://relay.test/p/p-5173/login")
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        assertEquals("vitejs.dev", submitted)
    }
}
