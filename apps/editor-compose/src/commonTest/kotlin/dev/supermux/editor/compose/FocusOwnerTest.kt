package dev.supermux.editor.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two linked editors: the focus moves from one to the other, the platform reports it in either order. */
class FocusOwnerTest {
    @Test fun theNewEditorsGainThenTheOldOnesLossKeepsTheSettingOn() {
        val o = FocusOwner<String>()
        assertTrue(o.changed("a", true))
        assertTrue(o.changed("b", true))
        assertTrue(o.changed("a", false), "a's late blur turned the setting off while b is focused")
        assertEquals("b", o.owner)
        assertEquals(false, o.changed("b", false))
    }

    @Test fun theOldOnesLossThenTheNewGain() {
        val o = FocusOwner<String>()
        o.changed("a", true)
        assertEquals(false, o.changed("a", false))
        assertTrue(o.changed("b", true))
        assertEquals("b", o.owner)
    }
}
