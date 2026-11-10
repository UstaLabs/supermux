package dev.supermux.ui.browser

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.MarkdownBody
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinkOpenerTest {
    @Test fun aChatLinkGoesToTheOpenerItsChatProvides() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            CompositionLocalProvider(LocalOpenLink provides { url: String -> opened += url }) { MarkdownBody("[Vite](https://vitejs.dev/)") }
        }
        onNodeWithText("Vite").performMouseInput { click(Offset(8f, height / 2f)) }; waitForIdle()
        assertEquals(listOf("https://vitejs.dev/"), opened)
        assertTrue(platform.openedUrls.isEmpty())
    }

    @Test fun withNoOpenerALinkOpensInTheSystemBrowserAsBefore() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) { MarkdownBody("[Vite](https://vitejs.dev/)") }
        onNodeWithText("Vite").performMouseInput { click(Offset(8f, height / 2f)) }; waitForIdle()
        assertEquals(listOf("https://vitejs.dev/"), platform.openedUrls)
    }

    @Test fun aPressWithThePlatformsModifierIsRememberedForTheLinkItOpens() = runComposeUiTest {
        val clicks = ClickModifiers()
        // Ctrl, as on Linux and Windows (apple = false); on Apple platforms it's ⌘, never either (#96).
        setPlatformContent { Box(Modifier.size(100.dp).testTag("t").focusable().trackClickModifiers(clicks, apple = false)) }
        onNodeWithTag("t").performMouseInput { click() }
        assertFalse(clicks.modified)
        onNodeWithTag("t").requestFocus()
        onNodeWithTag("t").performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag("t").performMouseInput { click() }
        assertTrue(clicks.modified)
    }

    @Test fun chatLinksOpenInBrowserTabsUnlessThePlatformSaysOtherwise() {
        // The web client's WEB_CAPS says otherwise (Ahmet's Q5); `:ui`'s tests can't see `:web`, so the review checks it.
        assertTrue(NO_CAPS.linksInBrowserTabs)
    }
}
