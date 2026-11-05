package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** A window with no sidebar (a torn-off one) wears the main window's chrome, minus the sidebar's controls. */
@OptIn(ExperimentalTestApi::class)
class DesktopWindowChromeTest {
    @Test
    fun withoutTheSidebarsControlsTheBandKeepsRoomOnlyForTheWindowControls() {
        assertEquals(
            ChromeInsets(start = 0.dp, end = LinuxWindowControlsWidth, band = LinuxTitleBarHeight),
            chromeInsets(ChromeOs.Linux, customChrome = true, sidebarControls = false),
        )
        assertEquals(
            ChromeInsets(start = 0.dp, end = 141.dp, band = WindowsTitleBarHeight),
            chromeInsets(ChromeOs.Windows, customChrome = true, windowsCaptionButtons = 141.dp, sidebarControls = false),
        )
        assertEquals(
            ChromeInsets(start = 70.dp, end = 0.dp, band = MacTitleBarHeight),
            chromeInsets(ChromeOs.MacOs, customChrome = true, macTrafficLights = 70.dp, sidebarControls = false),
        )
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Linux, customChrome = false, sidebarControls = false))
    }

    @Test
    fun aTornOffWindowsTopLeftStripClearsJustTheTrafficLights() = runComposeUiTest {
        var width = -1
        var x = -1f
        val insets = chromeInsets(ChromeOs.MacOs, customChrome = true, macTrafficLights = 70.dp, sidebarControls = false)
        setContent {
            CompositionLocalProvider(LocalWindowChromeInsets provides insets) {
                Box(Modifier.size(800.dp, 600.dp)) {
                    Box(Modifier.width(400.dp).height(32.dp).avoidWindowControls()) {
                        Box(
                            Modifier.fillMaxWidth().height(32.dp)
                                .onSizeChanged { width = it.width }
                                .onGloballyPositioned { x = it.positionInRoot().x },
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(400 - 70, width)
        assertEquals(70f, x, "its first tab starts right after the lights")
    }

    @Test
    fun aTornOffWindowsStripsKeepClearOfOnlyTheButtonsOnLinux() = runComposeUiTest {
        var leftWidth = -1
        var rightWidth = -1
        setContent {
            CompositionLocalProvider(
                LocalWindowChromeInsets provides chromeInsets(ChromeOs.Linux, customChrome = true, sidebarControls = false),
            ) {
                Row(Modifier.size(800.dp, 600.dp)) {
                    Box(Modifier.width(400.dp).height(32.dp).avoidWindowControls()) {
                        Box(Modifier.fillMaxWidth().height(32.dp).onSizeChanged { leftWidth = it.width })
                    }
                    Box(Modifier.width(400.dp).height(32.dp).avoidWindowControls()) {
                        Box(Modifier.fillMaxWidth().height(32.dp).onSizeChanged { rightWidth = it.width })
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(400, leftWidth, "no ☰ menu or sidebar toggle at the start")
        assertEquals(400 - 104, rightWidth, "the top-right strip leaves the buttons' width free")
    }
}
