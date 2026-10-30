package dev.supermux.webview.engine.linux

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
    @Test fun aLetterTypesItsCharacterAtItsPosition() {
        assertEquals(WpeKey(0x61, 38), KeyMapping.keyFor(KeyEvent.VK_A, 'a'.code))
        assertEquals(WpeKey(0x41, 38), KeyMapping.keyFor(KeyEvent.VK_A, 'A'.code))
        assertEquals(WpeKey(0x31, 10), KeyMapping.keyFor(KeyEvent.VK_1, '1'.code))
    }

    @Test fun turkishCharactersAreLatin1OrUnicodeKeysyms() {
        assertEquals(WpeKey(0xe7, 0), KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 'ç'.code))
        assertEquals(WpeKey(0x0100011f, 0), KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 'ğ'.code))
        assertEquals(WpeKey(0x01000131, 0), KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 'ı'.code))
        assertEquals(WpeKey(0x01000130, 0), KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 'İ'.code))
    }

    @Test fun japaneseIsAUnicodeKeysym() {
        assertEquals(0x01003042, KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 'あ'.code)?.keyval)
    }

    @Test fun namedKeysHaveTheirKeysymAndKeycode() {
        assertEquals(WpeKey(0xff0d, 36), KeyMapping.keyFor(KeyEvent.VK_ENTER, null))
        assertEquals(WpeKey(0xff51, 113), KeyMapping.keyFor(KeyEvent.VK_LEFT, null))
        assertEquals(WpeKey(0xffc2, 71), KeyMapping.keyFor(KeyEvent.VK_F5, null))
        assertEquals(WpeKey(0xffc9, 96), KeyMapping.keyFor(KeyEvent.VK_F12, null))
        assertEquals(WpeKey(0xffe1, 50), KeyMapping.keyFor(KeyEvent.VK_SHIFT, null))
        assertEquals(WpeKey(0x20, 65), KeyMapping.keyFor(KeyEvent.VK_SPACE, ' '.code))
    }

    @Test fun aControlCharacterFallsBackToTheNamedKey() {
        assertEquals(WpeKey(0xff08, 22), KeyMapping.keyFor(KeyEvent.VK_BACK_SPACE, 8))
        assertEquals(WpeKey(0xff0d, 36), KeyMapping.keyFor(KeyEvent.VK_ENTER, '\n'.code))
        assertEquals(WpeKey(0xffff, 119), KeyMapping.keyFor(KeyEvent.VK_DELETE, 0x7f))
    }

    @Test fun awtsUndefinedCharacterIsNoCharacter() {
        assertEquals(WpeKey(0xff52, 111), KeyMapping.keyFor(KeyEvent.VK_UP, KeyMapping.codePointOf(KeyEvent.CHAR_UNDEFINED.code)))
        assertNull(KeyMapping.codePointOf(KeyEvent.CHAR_UNDEFINED.code))
        assertNull(KeyMapping.codePointOf(0))
        assertEquals('ş'.code, KeyMapping.codePointOf('ş'.code))
    }

    @Test fun aKeyWithNoNameThatTypesNothingIsNotForwarded() {
        assertNull(KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, null))
        assertNull(KeyMapping.keyFor(KeyEvent.VK_UNDEFINED, 0xd800))
    }

    @Test fun modifierAndButtonBitsAreWpes() {
        assertEquals(1 or 2, KeyMapping.modifiers(shift = true, ctrl = true, alt = false, meta = false))
        assertEquals(4 or 8 or 16, KeyMapping.modifiers(shift = false, ctrl = false, alt = true, meta = true, capsLock = true))
        assertEquals((1 shl 8) or (1 shl 10), KeyMapping.buttons(primary = true, middle = false, secondary = true))
    }
}
