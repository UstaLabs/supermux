package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OverlayWindowTitleTest {
    // macOS finds an overlay window by its title to float it everywhere, and a bubble just closed can still be
    // listed under the same title: each window must have a title of its own, or the new one is never floated.
    @Test fun noTwoWindowsShareATitle() = assertNotEquals(uniqueOverlayTitle(BUBBLE_TITLE), uniqueOverlayTitle(BUBBLE_TITLE))

    @Test fun theTitleStillSaysWhichWindowItIs() = assertTrue(uniqueOverlayTitle(BUBBLE_TITLE).startsWith(BUBBLE_TITLE))
}
