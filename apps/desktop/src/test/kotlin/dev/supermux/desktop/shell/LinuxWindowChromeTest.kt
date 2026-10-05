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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.runtime.remember
import java.awt.event.MouseEvent
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
    fun macReservesNothingHereItsTrafficLightsHaveTheirOwnInset() {
        assertEquals(ChromeInsets.None, chromeInsets(ChromeOs.MacOs, customChrome = true))
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

    // ── the band's press / drag decisions ──

    @Test
    fun aPrimaryPressOnADragRegionInTheBandArmsADrag() {
        assertEquals(BandPress.ArmDrag, bandPressAction(MouseEvent.BUTTON1, 1, yPx = 10f, scale = 1.0, inDragRegion = true))
    }

    @Test
    fun aDoubleClickInTheBandTogglesMaximise() {
        assertEquals(BandPress.ToggleMaximise, bandPressAction(MouseEvent.BUTTON1, 2, yPx = 10f, scale = 1.0, inDragRegion = true))
    }

    @Test
    fun pressesOutsideTheBandOrADragRegionOrWithAnotherButtonAreIgnored() {
        assertEquals(BandPress.Ignore, bandPressAction(MouseEvent.BUTTON1, 1, yPx = 40f, scale = 1.0, inDragRegion = true), "below the band")
        assertEquals(BandPress.Ignore, bandPressAction(MouseEvent.BUTTON1, 2, yPx = 10f, scale = 1.0, inDragRegion = false), "a hole")
        assertEquals(BandPress.Ignore, bandPressAction(MouseEvent.BUTTON3, 1, yPx = 10f, scale = 1.0, inDragRegion = true), "right-click")
        assertEquals(BandPress.Ignore, bandPressAction(MouseEvent.BUTTON2, 2, yPx = 10f, scale = 1.0, inDragRegion = true), "middle")
    }

    @Test
    fun theBandScalesWithTheMonitor() {
        // 32 dp is 64 px at 2x: y=40 px is inside the band there, outside it at 1x.
        assertEquals(BandPress.ArmDrag, bandPressAction(MouseEvent.BUTTON1, 1, yPx = 40f, scale = 2.0, inDragRegion = true))
        assertEquals(BandPress.Ignore, bandPressAction(MouseEvent.BUTTON1, 1, yPx = 64f, scale = 2.0, inDragRegion = true))
    }

    @Test
    fun aMoveStartsOnlyPastTheSlop() {
        assertFalse(dragPastSlop(100, 10, 103, 13))
        assertTrue(dragPastSlop(100, 10, 104, 10))
        assertTrue(dragPastSlop(100, 10, 100, 6))
        assertEquals(4, BAND_DRAG_SLOP)
    }

    // ── the _NET_WM_MOVERESIZE message ──

    @Test
    fun theMoveResizeMessageCarriesPositionDirectionButtonAndSource() {
        assertEquals(
            listOf(640L, 300L, 3L, 1L, 1L),
            moveResizeMessageData(640, 300, WmMoveResizeDirection.Right).toList(),
        )
        assertEquals(8L, moveResizeMessageData(0, 0, WmMoveResizeDirection.Move)[2])
        assertEquals(0L, moveResizeMessageData(0, 0, WmMoveResizeDirection.TopLeft)[2])
        assertEquals(7L, moveResizeMessageData(0, 0, WmMoveResizeDirection.Left)[2])
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
                        LinuxWindowChromeInstall(MacChromeRegions(), frame, maximised, startResize = { resizes += it }),
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

    @Test
    fun aPrimaryPressOnAHandleStartsThatResize() = runComposeUiTest {
        val resizes = mutableListOf<WmMoveResizeDirection>()
        setContent {
            MaterialTheme {
                Box(Modifier.size(600.dp, 400.dp)) {
                    LinuxWindowChromeOverlay(
                        LinuxWindowChromeInstall(MacChromeRegions(), FakeFrame(), false, startResize = { resizes += it }),
                    )
                }
            }
        }
        waitForIdle()
        // Mouse injection keeps its position between calls: move onto each handle first.
        onNodeWithTag("linux_resize_Right").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        onNodeWithTag("linux_resize_BottomLeft").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        onNodeWithTag("linux_resize_Top").performMouseInput { moveTo(center); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        waitForIdle()
        assertEquals(
            listOf(WmMoveResizeDirection.Right, WmMoveResizeDirection.BottomLeft),
            resizes,
            "a primary press on a handle starts that resize; a secondary press does nothing",
        )
    }

    @Test
    fun aHoverIsDroppedWhenTheWindowGoesAway() = runComposeUiTest {
        var epoch by mutableStateOf(0)
        setContent {
            MaterialTheme {
                LinuxWindowControls(false, {}, {}, {}, pointerEpoch = epoch)
            }
        }
        onNodeWithTag("linux_window_close").performMouseInput { moveTo(center) }
        waitForIdle()
        assertEquals(WindowButtonState.Hovered, visualOf("linux_window_close").state)
        // Hidden to the tray / iconified / deactivated: no exit ever reaches Compose.
        epoch++
        waitForIdle()
        assertEquals(WindowButtonState.Idle, visualOf("linux_window_close").state)
    }

    @Test
    fun aKeyboardFocusedButtonShowsAFocusRing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                LinuxWindowControls(false, {}, {}, {})
            }
        }
        assertFalse(visualOf("linux_window_minimise").focusRing)
        onNodeWithTag("linux_window_minimise").requestFocus()
        waitForIdle()
        assertTrue(visualOf("linux_window_minimise").focusRing)
        assertFalse(visualOf("linux_window_close").focusRing)
        assertEquals(lightColorScheme().primary, windowButtonFocusRing(lightColorScheme()))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.visualOf(tag: String): WindowButtonVisual =
        onNodeWithTag(tag).fetchSemanticsNode().config[WindowButtonVisualKey]

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
                    remember { MainMenuState() },
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

    // ── keyboard access to the main menu ──

    private val testMenu = listOf(
        MainMenuGroup("File", 'F', listOf(MainMenuEntry.Action("New Session") {}, MainMenuEntry.Action("Settings…") {})),
        MainMenuGroup("Edit", 'E', listOf(MainMenuEntry.Action("Paste image") {})),
        MainMenuGroup("View", 'V', listOf(MainMenuEntry.Toggle("Show Sidebar", true) {})),
    )

    @Test
    fun f10AndAltMnemonicsPickTheirSection() {
        fun section(key: Key, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) =
            mainMenuShortcutSection(key, alt, ctrl, shift, meta = false, menu = testMenu)
        assertEquals("File", section(Key.F10))
        assertEquals("File", section(Key.F, alt = true))
        assertEquals("Edit", section(Key.E, alt = true))
        assertEquals("View", section(Key.V, alt = true))
        assertEquals(null, section(Key.F))
        assertEquals(null, section(Key.X, alt = true))
        assertEquals(null, section(Key.F10, shift = true))
        assertEquals(null, section(Key.E, alt = true, ctrl = true))
    }

    @Test
    fun theKeyboardFocusedMenuRowIsHighlighted() {
        for (cs in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(androidx.compose.ui.graphics.Color.Transparent, mainMenuRowBackground(cs, focused = false))
            assertEquals(cs.onSurface.copy(alpha = 0.14f), mainMenuRowBackground(cs, focused = true))
        }
    }

    @Test
    fun theShortcutsDoNothingWithoutAnAttachedMenu() {
        val state = MainMenuState()
        assertFalse(state.onWindowKey(Key.F10, alt = false, ctrl = false, shift = false, meta = false))
        assertFalse(state.isOpen)
    }

    @Test
    fun altEOpensTheMenuFocusedOnEditAndArrowsAndEnterWork() = runComposeUiTest {
        val ran = mutableListOf<String>()
        val menu = listOf(
            MainMenuGroup("File", 'F', listOf(MainMenuEntry.Action("New Session") { ran += "new" })),
            MainMenuGroup(
                "Edit",
                'E',
                listOf(MainMenuEntry.Action("Paste image") { ran += "paste" }, MainMenuEntry.Action("Paste text") { ran += "text" }),
            ),
        )
        val state = MainMenuState()
        setContent { MaterialTheme { MainMenuButton(menu, state) } }
        waitForIdle()
        runOnIdle { assertTrue(state.onWindowKey(Key.E, alt = true, ctrl = false, shift = false, meta = false)) }
        waitForIdle()
        onNodeWithText("Paste image").assertIsFocused()
        onNodeWithText("Paste image").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("Paste text").assertIsFocused()
        onNodeWithText("Paste text").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithText("Paste image").assertIsFocused()
        onNodeWithText("Paste image").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("paste"), ran)
        assertFalse(state.isOpen, "running an entry closes the menu")
    }

    @Test
    fun f10OpensAtFileAndEscapeCloses() = runComposeUiTest {
        val state = MainMenuState()
        setContent { MaterialTheme { MainMenuButton(testMenu, state) } }
        waitForIdle()
        runOnIdle { assertTrue(state.onWindowKey(Key.F10, alt = false, ctrl = false, shift = false, meta = false)) }
        waitForIdle()
        assertEquals("File", state.openSection)
        onNodeWithText("New Session").assertIsFocused()
        onNodeWithText("New Session").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(state.isOpen)
    }

    private fun Modifier.onWidth(block: (Int) -> Unit): Modifier = onSizeChanged { block(it.width) }
}
