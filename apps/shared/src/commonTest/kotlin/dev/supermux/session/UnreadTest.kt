package dev.supermux.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnreadTest {
    @Test fun unread_when_last_message_and_no_read_pointer() {
        assertTrue(isSessionUnread("2026-06-13T10:00:00.000Z", null))
        assertTrue(isSessionUnread("2026-06-13T10:00:00.000Z", ""))
    }

    @Test fun read_when_pointer_at_or_after_last_message() {
        assertFalse(isSessionUnread("2026-06-13T10:00:00.000Z", "2026-06-13T10:00:00.000Z"))
        assertFalse(isSessionUnread("2026-06-13T10:00:00.000Z", "2026-06-13T10:05:00.000Z"))
    }

    @Test fun unread_when_last_message_newer_than_pointer() {
        assertTrue(isSessionUnread("2026-06-13T10:05:00.000Z", "2026-06-13T10:00:00.000Z"))
    }

    @Test fun no_last_message_is_not_unread() {
        assertFalse(isSessionUnread(null, null))
        assertFalse(isSessionUnread("", "2026-06-13T10:00:00.000Z"))
        assertFalse(isSessionUnread(null, "2026-06-13T10:00:00.000Z"))
    }

    @Test fun advanceLastRead_is_monotonic() {
        assertEquals("b", advanceLastRead(null, "b"))
        assertEquals("b", advanceLastRead("a", "b"))
        assertEquals("b", advanceLastRead("b", "a"))
        assertEquals("b", advanceLastRead("b", "b"))
    }
}
