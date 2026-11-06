package dev.supermux.desktop.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Settings says about the Wayland hold-to-talk key (#62): what the desktop can't do, and what to do instead. */
class WaylandKeyNoteTest {
    // GNOME before 48, older KDE: no GlobalShortcuts portal at all.
    @Test fun withoutThePortalItNamesTheDesktopsThatHaveOne() = assertEquals(
        "Hold-to-talk from any app needs GNOME 48+ or KDE Plasma 5.27+; hold your Agent to talk.",
        waylandKeyNote(WaylandHotKey.Outcome.NO_PORTAL),
    )

    @Test fun aRefusalSaysHowToBeAskedAgain() = assertEquals(
        "The desktop wasn't allowed to give supermux this key. Pick it again here to be asked again.",
        waylandKeyNote(WaylandHotKey.Outcome.DECLINED),
    )

    // The portal answered but couldn't hand the key over: its backend failed, or its dialog went unanswered.
    @Test fun aPortalThatCouldNotBindTheKeySaysSo() = assertEquals(
        "The desktop couldn't give supermux this key; hold your Agent to talk, or pick it again here to try again.",
        waylandKeyNote(WaylandHotKey.Outcome.FAILED),
    )

    @Test fun boundOrNotAskedSaysNothing() {
        assertNull(waylandKeyNote(WaylandHotKey.Outcome.BOUND))
        assertNull(waylandKeyNote(null))
    }
}
