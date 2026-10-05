package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinuxWindowChromeTest {

    // ── chromeInsets ──

    @Test
    fun linuxCustomChromeReservesTheButtonsAtTheTopRight() {
        val i = chromeInsets(ChromeOs.Linux, customChrome = true)
        assertEquals(0.dp, i.start)
        assertEquals(LinuxWindowControlsWidth, i.end)
        assertEquals(LinuxTitleBarHeight, i.band)
        // Three 24 dp buttons, an 8 dp gap between them and an 8 dp margin each side.
        assertEquals(104.dp, LinuxWindowControlsWidth)
    }

    @Test
    fun theSystemFrameReservesNothing() {
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Linux, customChrome = false))
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Other, customChrome = true))
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.Other, customChrome = false))
    }

    @Test
    fun macKeepsTheTrafficLightsAtTheTopLeft() {
        val i = chromeInsets(ChromeOs.MacOs, customChrome = true)
        assertEquals(MacTrafficLightsWidth, i.start)
        assertEquals(0.dp, i.end)
        assertEquals(MacTitleBarHeight, i.band)
    }

    @Test
    fun osNamesMapToChromeOs() {
        assertEquals(ChromeOs.MacOs, ChromeOs.of("Mac OS X"))
        assertEquals(ChromeOs.Linux, ChromeOs.of("Linux"))
        assertEquals(ChromeOs.Other, ChromeOs.of("Windows 11"))
        assertEquals(ChromeOs.Other, ChromeOs.of(null))
    }

    // ── the engage gate ──

    @Test
    fun customChromeNeedsLinuxWindowMoveAndNativeResize() {
        assertTrue(LinuxWindowChrome.shouldEngage(linux = true, optOut = null, windowMoveSupported = true, nativeResize = true))
        assertFalse(LinuxWindowChrome.shouldEngage(linux = false, optOut = null, windowMoveSupported = true, nativeResize = true))
        assertFalse(LinuxWindowChrome.shouldEngage(linux = true, optOut = null, windowMoveSupported = false, nativeResize = true))
        assertFalse(
            LinuxWindowChrome.shouldEngage(linux = true, optOut = null, windowMoveSupported = true, nativeResize = false),
            "without a native resize the window could not be resized: keep the system frame",
        )
    }

    @Test
    fun theOptOutKeepsTheSystemTitleBar() {
        for (v in listOf("1", "true", "TRUE", " yes ")) {
            assertFalse(LinuxWindowChrome.shouldEngage(true, v, windowMoveSupported = true, nativeResize = true), v)
        }
        assertTrue(LinuxWindowChrome.shouldEngage(true, "0", windowMoveSupported = true, nativeResize = true))
        assertTrue(LinuxWindowChrome.shouldEngage(true, "", windowMoveSupported = true, nativeResize = true))
    }

    // ── button actions over a fake frame ──

    private class FakeFrame(override var extendedState: Int = Frame.NORMAL) : WindowControlTarget {
        var closeRequests = 0

        override fun requestClose() {
            closeRequests++
        }
    }

    @Test
    fun minimiseIconifiesAndKeepsTheMaximisedBits() {
        val f = FakeFrame(Frame.MAXIMIZED_BOTH)
        WindowControlActions.minimise(f)
        assertEquals(Frame.MAXIMIZED_BOTH or Frame.ICONIFIED, f.extendedState)
    }

    @Test
    fun maximiseTogglesBothWays() {
        val f = FakeFrame()
        WindowControlActions.toggleMaximise(f)
        assertEquals(Frame.MAXIMIZED_BOTH, f.extendedState)
        assertTrue(WindowControlActions.isMaximised(f.extendedState))
        WindowControlActions.toggleMaximise(f)
        assertEquals(Frame.NORMAL, f.extendedState)
    }

    @Test
    fun maximisedInOneDirectionOnlyMaximisesFully() {
        val f = FakeFrame(Frame.MAXIMIZED_HORIZ)
        assertFalse(WindowControlActions.isMaximised(f.extendedState))
        WindowControlActions.toggleMaximise(f)
        assertEquals(Frame.MAXIMIZED_BOTH, f.extendedState)
    }

    @Test
    fun closeIsTheWindowsCloseRequest() {
        val f = FakeFrame()
        WindowControlActions.close(f)
        assertEquals(1, f.closeRequests)
        assertEquals(Frame.NORMAL, f.extendedState, "close must not touch the frame state")
    }

    // ── theme colours ──

    @Test
    fun buttonBackgroundsComeFromTheSurfaceVariantAndDeepen() {
        for (cs in listOf(lightColorScheme(), darkColorScheme())) {
            val idle = windowButtonBackground(cs, WindowButtonState.Idle)
            val hovered = windowButtonBackground(cs, WindowButtonState.Hovered)
            val pressed = windowButtonBackground(cs, WindowButtonState.Pressed)
            assertEquals(cs.surfaceVariant.copy(alpha = 0.6f), idle)
            assertNotEquals(idle, hovered)
            assertNotEquals(hovered, pressed)
            assertEquals(1f, hovered.alpha, "hover is opaque, over the surface variant")
            assertEquals(cs.onSurface, windowButtonGlyph(cs))
        }
    }

    @Test
    fun lightAndDarkThemesGetDifferentButtons() {
        val light = lightColorScheme()
        val dark = darkColorScheme()
        for (state in WindowButtonState.entries) {
            assertNotEquals(windowButtonBackground(light, state), windowButtonBackground(dark, state), state.name)
        }
        assertNotEquals(windowButtonGlyph(light), windowButtonGlyph(dark))
    }

    // ── geometry ──

    @Test
    fun onlyTheTopRightElementAvoidsTheButtons() {
        assertTrue(touchesTopEnd(top = 0f, right = 800f, rootWidth = 800f, bandPx = 32f))
        assertFalse(touchesTopEnd(top = 0f, right = 500f, rootWidth = 800f, bandPx = 32f), "a left split")
        assertFalse(touchesTopEnd(top = 400f, right = 800f, rootWidth = 800f, bandPx = 32f), "a lower split")
    }

    @Test
    fun shortcutsReadAsAMenuShowsThem() {
        assertEquals("Ctrl+N", MenuShortcut(Key.N, ctrl = true).label)
        assertEquals("Ctrl+Shift+V", MenuShortcut(Key.V, ctrl = true, shift = true).label)
        assertEquals(KeyShortcut(Key.N, ctrl = true), MenuShortcut(Key.N, ctrl = true).toKeyShortcut())
    }

    // ── Compose: the buttons ──

    @Test
    fun theThreeButtonsRenderAndDispatch() = runComposeUiTest {
        val clicks = mutableListOf<String>()
        setContent {
            MaterialTheme {
                LinuxWindowControls(
                    maximised = false,
                    onMinimise = { clicks += "min" },
                    onToggleMaximise = { clicks += "max" },
                    onClose = { clicks += "close" },
                )
            }
        }
        onNodeWithTag("linux_window_minimise").assertIsDisplayed().performClick()
        onNodeWithTag("linux_window_maximise").assertIsDisplayed().performClick()
        onNodeWithTag("linux_window_close").assertIsDisplayed().performClick()
        assertEquals(listOf("min", "max", "close"), clicks)
        assertEquals(0, onAllNodesWithTag("linux_window_restore").fetchSemanticsNodes().size)
    }

    @Test
    fun aMaximisedWindowShowsRestore() = runComposeUiTest {
        var toggles = 0
        setContent {
            MaterialTheme {
                LinuxWindowControls(maximised = true, onMinimise = {}, onToggleMaximise = { toggles++ }, onClose = {})
            }
        }
        onNodeWithTag("linux_window_restore").assertIsDisplayed().performClick()
        assertEquals(1, toggles)
        assertEquals(0, onAllNodesWithTag("linux_window_maximise").fetchSemanticsNodes().size)
    }

    @Test
    fun theButtonsArePunchedOutOfTheDragBand() = runComposeUiTest {
        val regions = MacChromeRegions()
        setContent {
            CompositionLocalProvider(LocalMacWindowChrome provides regions) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 32.dp)) {
                        Box(Modifier.fillMaxSize().macTitleBarDragRegion("band"))
                        LinuxWindowControls(false, {}, {}, {}, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
                    }
                }
            }
        }
        waitForIdle()
        // Test density 1: the controls are the last 104 px; the first button starts at 304.
        assertTrue(regions.allowsNativeDrag(androidx.compose.ui.geometry.Offset(100f, 16f)), "the band drags")
        assertFalse(regions.allowsNativeDrag(androidx.compose.ui.geometry.Offset(320f, 16f)), "minimise stays a button")
        assertFalse(regions.allowsNativeDrag(androidx.compose.ui.geometry.Offset(385f, 16f)), "close stays a button")
    }

    @Test
    fun theOverlayDrivesTheFrameAndHidesResizeWhenMaximised() = runComposeUiTest {
        val frame = FakeFrame()
        val resizes = mutableListOf<WmMoveResizeDirection>()
        var maximised by mutableStateOf(false)
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 400.dp)) {
                    LinuxWindowChromeOverlay(
                        LinuxWindowChromeInstall(MacChromeRegions(), frame, maximised) { resizes += it },
                    )
                }
            }
        }
        onNodeWithTag("linux_window_maximise").performClick()
        assertEquals(Frame.MAXIMIZED_BOTH, frame.extendedState)
        onNodeWithTag("linux_window_minimise").performClick()
        assertEquals(Frame.MAXIMIZED_BOTH or Frame.ICONIFIED, frame.extendedState)
        onNodeWithTag("linux_window_close").performClick()
        assertEquals(1, frame.closeRequests)
        assertEquals(8, WmMoveResizeDirection.entries.count { onAllNodesWithTag("linux_resize_${it.name}").fetchSemanticsNodes().isNotEmpty() })
        maximised = true
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("linux_resize_Right").fetchSemanticsNodes().size, "no resize while maximised")
    }

    // ── Compose: the top-right strip keeps its content out from under the buttons ──

    @Test
    fun theTopRightElementIsInsetByTheButtons() = runComposeUiTest {
        var leftWidth = -1
        var rightWidth = -1
        setContent {
            CompositionLocalProvider(LocalWindowChromeInsets provides chromeInsets(ChromeOs.Linux, customChrome = true)) {
                Row(Modifier.size(800.dp, 600.dp)) {
                    Box(Modifier.width(400.dp).height(32.dp).avoidWindowControls()) {
                        Box(Modifier.fillMaxWidth().height(32.dp).testTag("left").onWidth { leftWidth = it })
                    }
                    Box(Modifier.width(400.dp).height(32.dp).avoidWindowControls()) {
                        Box(Modifier.fillMaxWidth().height(32.dp).testTag("right").onWidth { rightWidth = it })
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(400, leftWidth, "the left strip does not touch the buttons")
        assertEquals(400 - 104, rightWidth, "the top-right strip leaves the buttons' width free")
    }

    @Test
    fun withoutCustomChromeNothingIsInset() = runComposeUiTest {
        var width = -1
        setContent {
            Box(Modifier.size(400.dp, 32.dp).avoidWindowControls()) {
                Box(Modifier.fillMaxWidth().height(32.dp).onWidth { width = it })
            }
        }
        waitForIdle()
        assertEquals(400, width)
    }

    // ── Compose: the main menu dropdown ──

    @Test
    fun theMainMenuButtonOpensTheMenuAndRunsAnEntry() = runComposeUiTest {
        var opened = 0
        var sidebar = true
        setContent {
            MaterialTheme {
                MainMenuButton(
                    listOf(
                        MainMenuGroup("File", 'F', listOf(MainMenuEntry.Action("Settings…") { opened++ })),
                        MainMenuGroup("View", 'V', listOf(MainMenuEntry.Toggle("Show Sidebar", sidebar) { sidebar = !sidebar })),
                    ),
                )
            }
        }
        onNodeWithTag("main_menu_button").performClick()
        onNodeWithText("Settings…").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        onNodeWithTag("main_menu_button").performClick()
        onNodeWithText("Show Sidebar").performClick()
        assertFalse(sidebar)
    }

    private fun Modifier.onWidth(block: (Int) -> Unit): Modifier = onSizeChanged { block(it.width) }
}
