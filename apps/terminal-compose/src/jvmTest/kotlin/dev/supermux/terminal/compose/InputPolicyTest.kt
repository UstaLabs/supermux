package dev.supermux.terminal.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.MouseButton
import dev.supermux.terminal.TerminalModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who owns a pointer gesture: this surface, or the program on the other end of the pty.
 *
 * Two layers, asserted separately and on purpose:
 *
 * 1. **The policy**, as a table. It is a pure function of the negotiated modes, the gesture and the
 *    modifiers, so the table IS the specification — every row of it can be read without knowing
 *    anything about Compose.
 * 2. **The bytes**, end to end. A real [Terminal] on a real session on the REAL pinned Ghostty
 *    engine, driven through Compose's own input injection, with every `TerminalEffect.Input` byte
 *    recorded ([ByteRecorder]). "Local" is proved by zero bytes on the wire, "remote" by the exact
 *    sequence xterm's SGR protocol defines — neither of which a test of this package's own function
 *    calls could establish.
 */
class InputPolicyTest {

    private val shell = TerminalModes(alternateScreen = false, mouseTracking = false, bracketedPaste = false)
    private val sgrMouse = TerminalModes(alternateScreen = false, mouseTracking = true, bracketedPaste = false)
    private val altSgrMouse = TerminalModes(alternateScreen = true, mouseTracking = true, bracketedPaste = false)
    private val altPlain = TerminalModes(alternateScreen = true, mouseTracking = false, bracketedPaste = false)

    // ----------------------------------------------------------------- the table ----

    private data class Row(
        val what: String,
        val modes: TerminalModes,
        val intent: PointerIntent,
        val device: PointerDevice,
        val modifiers: Int,
        val route: PointerRoute,
    )

    @Test fun thePolicyTable() {
        val table = listOf(
            // The shell: the surface's own history and its own selection, always.
            Row("primary + mouse off + wheel", shell, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.LOCAL_HISTORY),
            Row("primary + mouse off + finger drag", shell, PointerIntent.DRAG, PointerDevice.TOUCH, Modifiers.NONE, PointerRoute.LOCAL_HISTORY),
            Row("primary + mouse off + mouse drag", shell, PointerIntent.DRAG, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.LOCAL_SELECTION),
            Row("primary + mouse off + press", shell, PointerIntent.PRESS, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.LOCAL_SELECTION),
            Row("mouse off + long press", shell, PointerIntent.LONG_PRESS, PointerDevice.TOUCH, Modifiers.NONE, PointerRoute.LOCAL_SELECTION),

            // The program asked for the mouse: it gets the mouse.
            Row("alternate + SGR mouse + wheel", altSgrMouse, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + press", sgrMouse, PointerIntent.PRESS, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + drag", sgrMouse, PointerIntent.DRAG, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + release", sgrMouse, PointerIntent.RELEASE, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + hover", sgrMouse, PointerIntent.HOVER, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + finger drag", sgrMouse, PointerIntent.DRAG, PointerDevice.TOUCH, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),

            // Shift is the user's override, and a long press is its touch equivalent.
            Row("SGR mouse + Shift + wheel", sgrMouse, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.SHIFT, PointerRoute.LOCAL_HISTORY),
            Row("SGR mouse + Shift + drag", sgrMouse, PointerIntent.DRAG, PointerDevice.MOUSE, Modifiers.SHIFT, PointerRoute.LOCAL_SELECTION),
            Row("SGR mouse + Shift + press", sgrMouse, PointerIntent.PRESS, PointerDevice.MOUSE, Modifiers.SHIFT, PointerRoute.LOCAL_SELECTION),
            Row("SGR mouse + long press", sgrMouse, PointerIntent.LONG_PRESS, PointerDevice.TOUCH, Modifiers.NONE, PointerRoute.LOCAL_SELECTION),

            // Other modifiers are the program's business, not an override.
            Row("SGR mouse + Ctrl + wheel", sgrMouse, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.CTRL, PointerRoute.REMOTE_MOUSE),
            Row("SGR mouse + Alt + press", sgrMouse, PointerIntent.PRESS, PointerDevice.MOUSE, Modifiers.ALT, PointerRoute.REMOTE_MOUSE),

            // The alternate screen has no history to scroll: the wheel belongs to the program's own
            // modes, whatever the engine's encoder makes of them.
            Row("alternate + mouse off + wheel", altPlain, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.REMOTE_MOUSE),
            Row("alternate + mouse off + Shift + wheel", altPlain, PointerIntent.WHEEL, PointerDevice.MOUSE, Modifiers.SHIFT, PointerRoute.LOCAL_HISTORY),
            Row("alternate + mouse off + press", altPlain, PointerIntent.PRESS, PointerDevice.MOUSE, Modifiers.NONE, PointerRoute.LOCAL_SELECTION),
        )
        val failures = table.mapNotNull { row ->
            val actual = TerminalInputPolicy.route(row.modes, row.intent, row.device, row.modifiers)
            if (actual == row.route) null else "${row.what}: expected ${row.route}, got $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test fun aWheelDeltaBecomesTheXtermButtonItMeans() {
        assertEquals(MouseButton.WHEEL_DOWN, TerminalInputPolicy.wheelButton(Offset(0f, 1f)))
        assertEquals(MouseButton.WHEEL_UP, TerminalInputPolicy.wheelButton(Offset(0f, -1f)))
        assertEquals(MouseButton.WHEEL_RIGHT, TerminalInputPolicy.wheelButton(Offset(1f, 0f)))
        assertEquals(MouseButton.WHEEL_LEFT, TerminalInputPolicy.wheelButton(Offset(-1f, 0f)))
        // A diagonal trackpad flick reports one direction, not two.
        assertEquals(MouseButton.WHEEL_DOWN, TerminalInputPolicy.wheelButton(Offset(0.4f, 1f)))
        assertEquals(MouseButton.NONE, TerminalInputPolicy.wheelButton(Offset.Zero))
        assertEquals(MouseButton.NONE, TerminalInputPolicy.wheelButton(Offset(Float.NaN, 1f)))

        // One notch is one event; a fraction of a notch is still one; a burst is capped.
        assertEquals(1, TerminalInputPolicy.wheelNotches(Offset(0f, 0.2f)))
        assertEquals(3, TerminalInputPolicy.wheelNotches(Offset(0f, 2.4f)))
        assertEquals(TerminalInputPolicy.MAX_NOTCHES, TerminalInputPolicy.wheelNotches(Offset(0f, 9999f)))
    }

    @Test fun theHitTestFollowsTheScrollOffsetAndStaysOnTheGrid() {
        val metrics = CellMetrics(width = 8f, height = 16f, baseline = 12f, lineHeight = 14f)
        assertEquals(TerminalCellPosition(0, 0), cellAt(Offset(0f, 0f), metrics, 0f, 40, 10))
        assertEquals(TerminalCellPosition(2, 1), cellAt(Offset(20f, 20f), metrics, 0f, 40, 10))
        // The painter shifted the grid up by 8px, so the pointer is 8px further down the content.
        assertEquals(TerminalCellPosition(2, 1), cellAt(Offset(20f, 12f), metrics, 8f, 40, 10))
        // Below the last row (the chrome strip) and past the last column: clamped, never off-grid.
        assertEquals(TerminalCellPosition(39, 9), cellAt(Offset(9999f, 9999f), metrics, 0f, 40, 10))
        assertEquals(TerminalCellPosition(0, 0), cellAt(Offset(-5f, -5f), metrics, 0f, 40, 10))
    }

    // ----------------------------------------------------------------- the bytes ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun aShellWheelScrollsHistoryAndSendsNothing() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()
        fixture.recorder.clear()
        assertTrue(fixture.scroll.following)

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(2, 2))
            scroll(-3f)
        }
        waitForIdle()

        assertEquals(0, fixture.engine.mouseCalls.get(), "a shell wheel became a mouse event")
        fixture.assertSilence("a shell wheel")
        assertTrue(!fixture.scroll.following, "the wheel did not walk back into history")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anApplicationWheelIsEncodedByTheEngineAndNeverScrollsHistory() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        enableSgrMouse(fixture, alternateScreen = true)
        val anchored = fixture.scroll.position

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(2, 1))
            scroll(1f)
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().isNotEmpty() }
        waitForIdle()

        // xterm's wheel "buttons" under SGR (1006): 64 = up, 65 = down, at column 3, row 2 (1-based).
        assertEquals("<ESC>[<65;3;2M", fixture.recorded())
        assertEquals(anchored, fixture.scroll.position, "the program's wheel also scrolled the surface")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anApplicationPressDragAndReleaseCarryTheCellsTheyLandedOn() = terminalInputTest { fixture ->
        enableSgrMouse(fixture)

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(3, 1))
            press()
            moveTo(fixture.centreOf(5, 2))
            release()
        }
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorded().count { it == 'M' || it == 'm' } >= 3 }
        waitForIdle()

        // Press at (3,1) -> `ESC[<0;4;2M`; the drag reports button 0 held (32 = motion) at (5,2);
        // the release is the lower-case `m` of SGR at the cell it happened in.
        assertEquals("<ESC>[<0;4;2M<ESC>[<32;6;3M<ESC>[<0;6;3m", fixture.recorded())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun shiftTakesTheWheelBackFromTheProgram() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        enableSgrMouse(fixture)
        assertTrue(fixture.scroll.following)

        // The accessory bar's Shift is the same modifier state a hardware Shift contributes, and it
        // is the one an injected pointer event can carry on every platform.
        fixture.accessories.shift = true
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(2, 2))
            scroll(-3f)
        }
        waitForIdle()

        fixture.assertSilence("a Shift-wheel")
        assertEquals(0, fixture.engine.mouseCalls.get(), "Shift did not take the wheel back")
        assertTrue(!fixture.scroll.following, "Shift-wheel did not scroll this surface's history")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aLongPressIsNeverAButtonTheProgramSees() = terminalInputTest { fixture ->
        enableSgrMouse(fixture)

        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(fixture.centreOf(4, 3))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        waitForIdle()

        // The press and the release themselves are the program's (it asked for the mouse), but no
        // long-press button is invented and nothing is sent twice.
        val recorded = fixture.recorded()
        assertTrue(recorded.startsWith("<ESC>[<0;5;4M"), "the press was not reported: $recorded")
        assertTrue(recorded.endsWith("<ESC>[<0;5;4m"), "the release was not reported: $recorded")
        assertEquals(2, fixture.engine.mouseCalls.get(), "a long press produced an extra mouse event")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aClickOpensAHyperlinkAndHoldingItDoesNot() = terminalInputTest { fixture ->
        // OSC 8: the shell's own hyperlink, already carried by every frame as TerminalLink.
        fixture.feed("\u001b]8;;https://example.com/a\u001b\\link\u001b]8;;\u001b\\")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.links.isNotEmpty() }
        waitForIdle()

        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(1, 0))
            press()
            release()
        }
        waitForIdle()
        assertEquals(listOf("https://example.com/a"), fixture.links)
        fixture.assertSilence("a click on a link")

        // Holding it is the start of a selection, not a click: the host is not asked to open it.
        // The press and the release are injected separately so the surface's long-press timer can
        // actually run between them.
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(1, 0))
            press()
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performMouseInput { release() }
        waitForIdle()
        assertEquals(1, fixture.links.size, "a long press on a link opened it")

        // Neither does a drag that started on it: that is a selection, not a click.
        onNodeWithTag(INPUT_TAG).performMouseInput {
            moveTo(fixture.centreOf(1, 0))
            press()
            moveTo(fixture.centreOf(3, 0))
            release()
        }
        waitForIdle()
        assertEquals(1, fixture.links.size, "a drag off a link opened it")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aTouchDragInTheShellScrollsAndStaysSilent() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()
        fixture.recorder.clear()

        onNodeWithTag(INPUT_TAG).performTouchInput {
            down(topCenter + Offset(0f, 8f))
            repeat(8) {
                advanceEventTime(24)
                moveBy(Offset(0f, 12f))
            }
            repeat(4) {
                advanceEventTime(80)
                moveBy(Offset.Zero)
            }
            up()
        }
        waitForIdle()

        fixture.assertSilence("a touch drag in the shell")
        assertEquals(0, fixture.engine.mouseCalls.get())
        assertTrue(!fixture.scroll.following, "the drag did not walk back into history")
    }
}
