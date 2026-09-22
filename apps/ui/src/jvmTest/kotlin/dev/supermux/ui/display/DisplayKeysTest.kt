package dev.supermux.ui.display

import androidx.compose.ui.input.key.Key
import dev.supermux.display.VncInput
import dev.supermux.net.Keysyms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The commonMain `Key` → X11 keysym table (cluster G4), which replaced desktop's
 * `java.awt.event.KeyEvent.VK_*` switch. `:ui` cannot name AWT, and Android's hidden keyboard field
 * had the same table under different key constants — this pins the union both now use.
 */
class DisplayKeysTest {

    @Test fun the_special_key_table_covers_the_keys_both_hosts_forwarded() {
        assertEquals(VncInput.SpecialKey.ENTER, specialKeyFor(Key.Enter))
        assertEquals(VncInput.SpecialKey.ENTER, specialKeyFor(Key.NumPadEnter))
        assertEquals(VncInput.SpecialKey.BACKSPACE, specialKeyFor(Key.Backspace))
        assertEquals(VncInput.SpecialKey.TAB, specialKeyFor(Key.Tab))
        assertEquals(VncInput.SpecialKey.ESCAPE, specialKeyFor(Key.Escape))
        assertEquals(VncInput.SpecialKey.ARROW_LEFT, specialKeyFor(Key.DirectionLeft))
        assertEquals(VncInput.SpecialKey.ARROW_UP, specialKeyFor(Key.DirectionUp))
        assertEquals(VncInput.SpecialKey.ARROW_RIGHT, specialKeyFor(Key.DirectionRight))
        assertEquals(VncInput.SpecialKey.ARROW_DOWN, specialKeyFor(Key.DirectionDown))
    }

    @Test fun an_ordinary_letter_is_not_a_special_key() {
        assertNull(specialKeyFor(Key.A))
        assertNull(specialKeyFor(Key.Spacebar))
        assertNull(specialKeyFor(Key.F1))
    }

    @Test fun the_special_keys_map_onto_the_rfb_keysyms() {
        // The desktop table's whole point: VK_LEFT ended up as XK_Left on the wire.
        assertEquals(Keysyms.LEFT, VncInput.keysymForSpecial(specialKeyFor(Key.DirectionLeft)!!))
        assertEquals(Keysyms.RETURN, VncInput.keysymForSpecial(specialKeyFor(Key.Enter)!!))
        assertEquals(Keysyms.ESCAPE, VncInput.keysymForSpecial(specialKeyFor(Key.Escape)!!))
        assertEquals(Keysyms.BACKSPACE, VncInput.keysymForSpecial(specialKeyFor(Key.Backspace)!!))
        assertEquals(Keysyms.TAB, VncInput.keysymForSpecial(specialKeyFor(Key.Tab)!!))
    }
}
