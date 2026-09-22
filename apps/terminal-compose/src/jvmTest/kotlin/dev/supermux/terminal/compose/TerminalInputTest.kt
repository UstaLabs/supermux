package dev.supermux.terminal.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import dev.supermux.terminal.KeyAction
import dev.supermux.terminal.Modifiers
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keyboard, the accessory bar and paste: what actually reaches the program.
 *
 * Every assertion here is on the BYTES the engine encoded ([ByteRecorder]) or on the [TerminalKey]s
 * the surface handed it, never on this package's internal calls — because the thing being tested is
 * that this layer hands the ENGINE the right event and lets Ghostty's pinned encoder decide the
 * rest. Application cursor mode, the kitty keyboard protocol and bracketed paste are all negotiated
 * by the program through the same session, so a test that turns them on with the real escape
 * sequence and then reads the wire proves the whole path.
 */
class TerminalInputTest {

    // Compose's own desktop factory: the only way to inject a key event with an exact code point
    // AND modifier state (the test harness's `pressKey` reports no modifiers at all on this
    // platform, which would make a Ctrl-C test assert nothing).
    @OptIn(InternalComposeUiApi::class)
    private fun key(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        codePoint: Int = 0,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = alt,
        isShiftPressed = shift,
    )

    /**
     * A press and its release, and then WAIT for the session's owner coroutine to have run both.
     * `session.key` only enqueues; a test that asserts on the next line would race the engine.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.press(
        fixture: InputFixture,
        key: Key,
        codePoint: Int = 0,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        events: Int = 2,
    ) {
        val before = fixture.engine.keyCalls.get()
        onNodeWithTag(INPUT_TAG).performKeyPress(
            key(key, KeyEventType.KeyDown, codePoint, ctrl, alt, shift),
        )
        onNodeWithTag(INPUT_TAG).performKeyPress(
            key(key, KeyEventType.KeyUp, codePoint, ctrl, alt, shift),
        )
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keyCalls.get() >= before + events }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.settle(fixture: InputFixture, expected: Int) {
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.recorder.bytes().size >= expected }
        waitForIdle()
    }

    // ----------------------------------------------------------------- hardware keys ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun controlAndAltChordsAreEncodedFromThePhysicalKey() = terminalInputTest { fixture ->
        press(fixture, Key.C, codePoint = 3, ctrl = true)
        settle(fixture, 1)
        // 0x03, from the physical key: the platform's own `utf16CodePoint` for Ctrl-C is the C0
        // control itself, which must never be passed as this key's TEXT.
        assertEquals(listOf(3.toByte()), fixture.recorder.bytes().toList())
        assertEquals("", fixture.engine.keys.first().text, "a C0 control was passed as layout text")
        assertEquals(Modifiers.CTRL, fixture.engine.keys.first().modifiers)
        assertEquals(TerminalKeys.C, fixture.engine.keys.first().physicalCode)

        fixture.recorder.clear()
        fixture.engine.keys.clear()
        press(fixture, Key.A, codePoint = 'a'.code, alt = true)
        settle(fixture, 2)
        // Alt-a is ESC a — built by the encoder from the TEXT, which Alt (unlike Ctrl) never eats.
        assertEquals("<ESC>a", fixture.recorder.text())
        assertEquals("a", fixture.engine.keys.first().text)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun arrowKeysFollowTheApplicationCursorModeTheProgramSet() = terminalInputTest { fixture ->
        press(fixture, Key.DirectionUp)
        settle(fixture, 3)
        assertEquals("<ESC>[A", fixture.recorder.text())

        // DECCKM: the same key, a different sequence, decided by the engine's encoder from the
        // terminal's own mode — not by a branch in this package.
        fixture.recorder.clear()
        fixture.feed("\u001b[?1h")
        waitForIdle()
        press(fixture, Key.DirectionUp)
        settle(fixture, 3)
        assertEquals("<ESC>OA", fixture.recorder.text())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theKittyKeyboardProtocolIsHonouredOnceTheProgramNegotiatesIt() = terminalInputTest { fixture ->
        press(fixture, Key.Escape)
        settle(fixture, 1)
        assertEquals("<ESC>", fixture.recorder.text(), "plain Escape is one byte")

        // CSI > 1 u: push the kitty flags with "disambiguate escape codes", which is exactly the
        // case where Escape stops being a bare 0x1b.
        fixture.recorder.clear()
        fixture.feed("\u001b[>1u")
        waitForIdle()
        press(fixture, Key.Escape)
        settle(fixture, 4)
        assertEquals("<ESC>[27u", fixture.recorder.text())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun holdingAKeyRepeatsItAndReleasingItIsItsOwnEvent() = terminalInputTest { fixture ->
        val node = onNodeWithTag(INPUT_TAG)
        node.performKeyPress(key(Key.A, KeyEventType.KeyDown, 'a'.code))
        node.performKeyPress(key(Key.A, KeyEventType.KeyDown, 'a'.code))
        node.performKeyPress(key(Key.A, KeyEventType.KeyDown, 'a'.code))
        node.performKeyPress(key(Key.A, KeyEventType.KeyUp, 'a'.code))
        // On the KEY CALLS, not on the bytes: the release produces no byte of its own, so waiting
        // for "aaa" would race the event that this test is actually about.
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keys.size >= 4 }
        settle(fixture, 3)

        assertEquals(
            listOf(KeyAction.PRESS, KeyAction.REPEAT, KeyAction.REPEAT, KeyAction.RELEASE),
            fixture.engine.keys.map { it.action },
        )
        assertEquals("aaa", fixture.recorder.text(), "a held key types once per repeat, no more")
        // The release carries the key, never the character again.
        assertEquals("", fixture.engine.keys.last().text)

        // A key pressed again AFTER its release is a press, not a repeat.
        fixture.engine.keys.clear()
        node.performKeyPress(key(Key.A, KeyEventType.KeyDown, 'a'.code))
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keys.isNotEmpty() }
        assertEquals(KeyAction.PRESS, fixture.engine.keys.single().action)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aNonUsLayoutSendsItsOwnCharacterFromTheUsPhysicalKey() = terminalInputTest { fixture ->
        // The key where `;` sits on a US keyboard prints `ö` on a German one.
        press(fixture, Key.Semicolon, codePoint = 'ö'.code)
        settle(fixture, 2)
        assertEquals("ö", fixture.recorder.bytes().decodeToString())
        assertEquals(TerminalKeys.SEMICOLON, fixture.engine.keys.first().physicalCode)
        assertEquals("ö", fixture.engine.keys.first().text)

        // And an astral-plane character survives the UTF-16 surrogate pair it arrives as. A key with
        // no physical code at all (a soft keyboard's) is one event: there is no key to release.
        fixture.recorder.clear()
        press(fixture, Key.Unknown, codePoint = 0x1F600, events = 1)
        settle(fixture, 4)
        assertEquals("😀", fixture.recorder.bytes().decodeToString())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aKeyPressAndItsImeEchoTypeOneCharacter() = terminalInputTest { fixture ->
        val controller = controllerOf(fixture)
        press(fixture, Key.A, codePoint = 'a'.code)
        settle(fixture, 1)
        assertEquals("a", fixture.recorder.text())

        // The soft keyboard commits the same character the hardware key just produced: dropped.
        assertFalse(controller.router.commitText("a"), "the IME echo was typed a second time")
        waitForIdle()
        assertEquals("a", fixture.recorder.text())

        // Anything else IS typed — an IME is the only path for composed text and dictation.
        assertTrue(controller.router.commitText("b"))
        settle(fixture, 2)
        assertEquals("ab", fixture.recorder.text())

        // And a second copy of the same character, once the echo was already accounted for, is
        // someone genuinely typing it again.
        assertTrue(controller.router.commitText("b"))
        settle(fixture, 3)
        assertEquals("abb", fixture.recorder.text())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aPlatformTextEventThatRepeatsItsKeyDownIsIgnored() = terminalInputTest { fixture ->
        val node = onNodeWithTag(INPUT_TAG)
        node.performKeyPress(key(Key.A, KeyEventType.KeyDown, 'a'.code))
        // AWT's KEY_TYPED arrives as an Unknown-type Compose event carrying the same character.
        node.performKeyPress(key(Key.A, KeyEventType.Unknown, 'a'.code))
        node.performKeyPress(key(Key.A, KeyEventType.KeyUp, 'a'.code))
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.engine.keyCalls.get() >= 2 }
        waitForIdle()

        assertEquals("a", fixture.recorder.text())
        assertEquals(2, fixture.engine.keyCalls.get(), "the text event became a third key event")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theHarnessesOwnKeyInjectionReachesTheProgram() = terminalInputTest { fixture ->
        // One case through Compose's own `performKeyInput`, so the wiring is proved against events
        // the harness builds rather than only against events this test builds.
        onNodeWithTag(INPUT_TAG).performKeyInput { pressKey(Key.Enter) }
        settle(fixture, 1)
        assertEquals(listOf(0x0D.toByte()), fixture.recorder.bytes().toList())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anUnfocusedSurfaceTakesNoInput() = terminalInputTest(focused = false) { fixture ->
        // Nothing is focused, so nothing is typed: the surface never grabs a key it was not given.
        onNodeWithTag(INPUT_TAG).performKeyInput { pressKey(Key.A) }
        waitForIdle()
        fixture.assertSilence("a key sent to an unfocused surface")
    }

    // ----------------------------------------------------------------- paste ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun aPasteIsWrappedOnceByTheEngineAndIsNotASeriesOfKeys() = terminalInputTest { fixture ->
        fixture.feed("\u001b[?2004h")
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.modes.bracketedPaste }
        waitForIdle()
        fixture.recorder.clear()

        var result: Boolean? = null
        fixture.accessories.paste("echo hi\nls", onResult = { result = it })
        settle(fixture, 10)
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { result != null }

        assertEquals(true, result)
        // Wrapped ONCE, by the engine. The pasted newline stays a LINE FEED inside the brackets,
        // where the Enter KEY below is a carriage return: that difference is exactly how a program
        // in bracketed-paste mode tells pasted text from someone pressing Enter.
        assertEquals("<ESC>[200~echo hi\nls<ESC>[201~", fixture.recorder.text())
        assertEquals(1, fixture.engine.pasteCalls.get())
        assertEquals(0, fixture.engine.keyCalls.get(), "the paste was typed as key events")

        // The Enter KEY is a carriage return on its own, with no brackets around it.
        fixture.recorder.clear()
        press(fixture, Key.Enter)
        settle(fixture, 1)
        assertEquals(listOf(0x0D.toByte()), fixture.recorder.bytes().toList())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun anUnsafePasteIsRefusedUntilTheHostConfirmsIt() = terminalInputTest { fixture ->
        // No bracketed paste: a newline would run the command the moment it landed.
        var refused: Boolean? = null
        fixture.accessories.paste("rm -rf /\n", onResult = { refused = it })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { refused != null }
        waitForIdle()

        assertEquals(false, refused)
        fixture.assertSilence("a rejected paste")

        // The host confirmed with the user; the same text now goes.
        var allowed: Boolean? = null
        fixture.accessories.paste("rm -rf /\n", allowUnsafe = true, onResult = { allowed = it })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { allowed != null }
        settle(fixture, 9)
        assertEquals(true, allowed)
        assertEquals("rm -rf /\r", fixture.recorder.text())
    }

    // ----------------------------------------------------------------- accessory bar ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun anArmedModifierAppliesToTheNextKeyAndThenDisarms() = terminalInputTest { fixture ->
        fixture.accessories.toggleCtrl()
        assertTrue(fixture.accessories.ctrl)
        assertEquals(Modifiers.CTRL, fixture.accessories.armedModifiers)

        // A bar button: a physical key with no character of its own.
        fixture.accessories.sendKey(TerminalKeys.C)
        settle(fixture, 1)
        assertEquals(listOf(3.toByte()), fixture.recorder.bytes().toList())
        assertFalse(fixture.accessories.ctrl, "an armed modifier is one-shot")
        // Press AND release, so a program under the kitty protocol never sees a key stuck down.
        assertEquals(
            listOf(KeyAction.PRESS, KeyAction.RELEASE),
            fixture.engine.keys.map { it.action },
        )

        // And it applies to a HARDWARE key just the same: this is the phone-with-a-keyboard case.
        fixture.recorder.clear()
        fixture.engine.keys.clear()
        fixture.accessories.toggleCtrl()
        press(fixture, Key.C, codePoint = 'c'.code)
        settle(fixture, 1)
        assertEquals(listOf(3.toByte()), fixture.recorder.bytes().toList())
        assertFalse(fixture.accessories.ctrl)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun armedModifiersAreClearedWhenFocusLeaves() = terminalInputTest { fixture ->
        fixture.accessories.toggleCtrl()
        fixture.accessories.toggleAlt()
        assertTrue(fixture.accessories.armed)

        controllerOf(fixture).onFocusChanged(false)
        waitForIdle()

        assertFalse(fixture.accessories.armed, "a Ctrl armed against a terminal the user left")
        assertEquals(Modifiers.NONE, fixture.accessories.armedModifiers)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun typingReturnsTheSurfaceToTheBottom() = terminalInputTest { fixture ->
        fixture.feed((0 until 200).joinToString("") { "line-$it\r\n" })
        waitUntil(timeoutMillis = INPUT_TIMEOUT) { fixture.session.viewports.value.historyRows > 100 }
        waitForIdle()
        onNodeWithTag(INPUT_TAG).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()

        fixture.scroll.consumePx(-200f)
        assertFalse(fixture.scroll.following)

        press(fixture, Key.A, codePoint = 'a'.code)
        waitForIdle()
        assertTrue(fixture.scroll.following, "typing did not return the surface to the newest output")
    }

    /**
     * The surface's input controller. It is internal glue, but the IME seam ([TerminalKeyRouter])
     * and focus handling have to be driven from somewhere until Task 5 gives them a public path.
     */
    private fun controllerOf(fixture: InputFixture): TerminalInputController =
        fixture.accessories.boundSink() as TerminalInputController
}
