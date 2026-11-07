package dev.supermux.webview.engine.linux

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest

/** A `<select>`'s menu (I7): WPE shows none, so the engine's surface shows it, and WebKit gets the choice. */
@OptIn(ExperimentalTestApi::class)
class OptionMenuTest {
    private val items = "3\tOne\n1\tTwo\n4\tGroup\n9\tThree\n8\tOff"

    @Test fun aMenuIsDecodedWithItsItemsAndPlace() {
        val menu = WpeEvent.decode(WpeNative.OPTION_MENU, 7, 0, items, "10,20,120,24") as WpeEvent.OptionMenu
        assertEquals(7, menu.request)
        assertEquals(listOf("One", "Two", "Group", "Three", "Off"), menu.items.map { it.label })
        assertEquals(listOf(true, true, false, true, false), menu.items.map { it.choosable })
        assertEquals(0, menu.items.indexOfFirst { it.selected })
        assertEquals(listOf(10, 20, 120, 24), listOf(menu.x, menu.y, menu.width, menu.height))
        assertEquals(WpeEvent.OptionMenuClosed(7), WpeEvent.decode(WpeNative.OPTION_MENU_CLOSED, 7, 0, null, null))
    }

    @Test fun theEngineHoldsTheMenuAndAnswersTheChoice() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Html("<select></select>", WebOrigin("select")), WebOrigin("select").url)
        rig.engine.onEvent(WpeEvent.decode(WpeNative.OPTION_MENU, 7, 0, items, "10,20,120,24")!!)
        assertEquals(7, rig.engine.optionMenu?.request)
        rig.engine.chooseOption(3)
        assertEquals(Triple(true, "3", null), rig.view.answers[7])
        assertNull(rig.engine.optionMenu)
        rig.engine.onEvent(WpeEvent.decode(WpeNative.OPTION_MENU, 8, 0, items, "10,20,120,24")!!)
        rig.engine.onEvent(WpeEvent.OptionMenuClosed(8))
        assertNull(rig.engine.optionMenu, "WebKit closed it")
        rig.close()
    }

    private fun engineWithMenu(view: FakeWpeView) = LinuxWebEngine(
        EngineConfig(WebProfile.Ephemeral), view, WebKitScripts("webview", "webview-out-t", "webview-in-t"),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), WpeClipboard({ null }) {},
    )

    @Test fun anOptionIsChosenWithTheMouse() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineWithMenu(view)
        setContent { engine.Surface(Modifier.size(300.dp, 200.dp).testTag("page")) }
        runOnUiThread { engine.onEvent(WpeEvent.decode(WpeNative.OPTION_MENU, 7, 0, items, "10,20,120,24")!!) }
        onNodeWithText("Three").performClick()
        waitForIdle()
        assertEquals(Triple(true, "3", null), runOnUiThread { view.answers[7] })
        runOnUiThread { engine.release() }
    }

    @Test fun anOptionIsChosenWithTheKeysSkippingWhatCantBe() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineWithMenu(view)
        setContent { engine.Surface(Modifier.size(300.dp, 200.dp).testTag("page")) }
        runOnUiThread { engine.onEvent(WpeEvent.decode(WpeNative.OPTION_MENU, 7, 0, items, "10,20,120,24")!!) }
        waitForIdle()
        // From the selected One: down to Two, down past the group's label to Three, down past the disabled Off.
        onNodeWithText("One").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(Triple(true, "3", null), runOnUiThread { view.answers[7] })
        runOnUiThread { engine.release() }
    }

    @Test fun escapeClosesTheMenuAsItWas() = runComposeUiTest {
        val view = FakeWpeView()
        val engine = engineWithMenu(view)
        setContent { engine.Surface(Modifier.size(300.dp, 200.dp).testTag("page")) }
        runOnUiThread { engine.onEvent(WpeEvent.decode(WpeNative.OPTION_MENU, 7, 0, items, "10,20,120,24")!!) }
        waitForIdle()
        onNodeWithText("One").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(Triple(false, null, null), runOnUiThread { view.answers[7] })
        runOnUiThread { engine.release() }
    }
}
