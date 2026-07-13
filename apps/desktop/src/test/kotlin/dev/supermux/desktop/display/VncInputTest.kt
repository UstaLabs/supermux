package dev.supermux.desktop.display

import dev.supermux.net.Keysyms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M5-2 Task 2: [VncInput] — pure input-mapping helpers ported verbatim from
 * apps/android/.../display/VncInput.kt (minus scrcpyKeyName; scrcpy is dropped, see this
 * milestone's Goal). No UI, no session state — geometry + an X11 keysym lookup table.
 */
class VncInputTest {

    // ── mapToRemote ─────────────────────────────────────────────────────────────────

    @Test fun map_to_remote_scales_a_centered_point_1to1_when_view_matches_remote_aspect() {
        val (rx, ry) = VncInput.mapToRemote(50f, 25f, 100, 50, 100, 50)
        assertEquals(50, rx); assertEquals(25, ry)
    }

    @Test fun map_to_remote_letterboxes_a_wider_view_than_the_remote_aspect() {
        // view 200x50 hosting a 100x50 remote (1:1 scale would fit fully on height, letterbox left/right).
        val (rx, ry) = VncInput.mapToRemote(100f, 25f, 200, 50, 100, 50)
        assertEquals(50, rx); assertEquals(25, ry)
    }

    @Test fun map_to_remote_letterboxes_a_wider_remote_in_a_tall_narrow_view() {
        // Reverse of the pillarbox case: a WIDE remote (100x50) in a TALL/narrow view (50x100).
        // scale = min(50/100, 100/50) = 0.5 → image is 50x25, offX=0, offY=37.5 (top/bottom bars).
        // Center maps to the remote center.
        val (cx, cy) = VncInput.mapToRemote(25f, 50f, 50, 100, 100, 50)
        assertEquals(50, cx); assertEquals(25, cy)
        // Image top-left corner (0, 37.5) → remote (0,0); bottom-right (50, 62.5) → remote (w,h).
        val (tlx, tly) = VncInput.mapToRemote(0f, 37.5f, 50, 100, 100, 50)
        assertEquals(0, tlx); assertEquals(0, tly)
        val (brx, bry) = VncInput.mapToRemote(50f, 62.5f, 50, 100, 100, 50)
        assertEquals(100, brx); assertEquals(50, bry)
        // A click up in the TOP black bar (py=10 < 37.5) clamps sanely to the top edge (ry=0).
        val (bx, by) = VncInput.mapToRemote(25f, 10f, 50, 100, 100, 50)
        assertEquals(50, bx); assertEquals(0, by)
    }

    @Test fun map_to_remote_clamps_to_the_remote_bounds() {
        val (rx, ry) = VncInput.mapToRemote(-10f, -10f, 100, 50, 100, 50)
        assertEquals(0, rx); assertEquals(0, ry)

        val (rx2, ry2) = VncInput.mapToRemote(1000f, 1000f, 100, 50, 100, 50)
        assertEquals(100, rx2); assertEquals(50, ry2)
    }

    @Test fun map_to_remote_returns_origin_for_degenerate_dimensions() {
        assertEquals(0 to 0, VncInput.mapToRemote(10f, 10f, 0, 50, 100, 50))
        assertEquals(0 to 0, VncInput.mapToRemote(10f, 10f, 100, 50, 0, 50))
    }

    // ── keysymForChar ───────────────────────────────────────────────────────────────

    @Test fun keysym_for_char_maps_newline_and_carriage_return_to_return() {
        assertEquals(Keysyms.RETURN, VncInput.keysymForChar('\n'))
        assertEquals(Keysyms.RETURN, VncInput.keysymForChar('\r'))
    }

    @Test fun keysym_for_char_maps_tab() {
        assertEquals(Keysyms.TAB, VncInput.keysymForChar('\t'))
    }

    @Test fun keysym_for_char_maps_del_and_backspace_control_codes_to_backspace() {
        assertEquals(Keysyms.BACKSPACE, VncInput.keysymForChar(0x7F.toChar()))
        assertEquals(Keysyms.BACKSPACE, VncInput.keysymForChar(0x08.toChar()))
    }

    @Test fun keysym_for_char_maps_a_printable_ascii_char_to_its_codepoint() {
        assertEquals('a'.code.toLong(), VncInput.keysymForChar('a'))
        assertEquals('!'.code.toLong(), VncInput.keysymForChar('!'))
    }

    @Test fun keysym_for_char_returns_null_for_other_control_codes() {
        assertNull(VncInput.keysymForChar(0x01.toChar()))
    }

    // ── keysymForSpecial ────────────────────────────────────────────────────────────

    @Test fun keysym_for_special_covers_every_special_key() {
        assertEquals(Keysyms.RETURN, VncInput.keysymForSpecial(VncInput.SpecialKey.ENTER))
        assertEquals(Keysyms.BACKSPACE, VncInput.keysymForSpecial(VncInput.SpecialKey.BACKSPACE))
        assertEquals(Keysyms.TAB, VncInput.keysymForSpecial(VncInput.SpecialKey.TAB))
        assertEquals(Keysyms.ESCAPE, VncInput.keysymForSpecial(VncInput.SpecialKey.ESCAPE))
        assertEquals(Keysyms.LEFT, VncInput.keysymForSpecial(VncInput.SpecialKey.ARROW_LEFT))
        assertEquals(Keysyms.UP, VncInput.keysymForSpecial(VncInput.SpecialKey.ARROW_UP))
        assertEquals(Keysyms.RIGHT, VncInput.keysymForSpecial(VncInput.SpecialKey.ARROW_RIGHT))
        assertEquals(Keysyms.DOWN, VncInput.keysymForSpecial(VncInput.SpecialKey.ARROW_DOWN))
    }
}
