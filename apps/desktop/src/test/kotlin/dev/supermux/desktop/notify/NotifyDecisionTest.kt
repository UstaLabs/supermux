package dev.supermux.desktop.notify

import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5-3 Task 1: [NotifyDecision] (the pure viewed/muted/reply-kind guard, mirroring the broker's
 * `push/hook.ts:firePushForReply` adapted to desktop's local viewing signal) and
 * [NotificationDedup] (burst coalescing). No Compose, no Tray, no coroutines beyond plain
 * function calls — this is the load-bearing correctness layer per this milestone's Goal; the
 * actual OS toast (Task 2) is thin glue by comparison.
 */
class NotifyDecisionTest {

    private fun reply(text: String? = "hi", attachments: List<Attachment>? = null) =
        LogEntry(id = "m1", ts = "2026-07-10T00:00:00Z", direction = "outbound", op = "reply", text = text, attachments = attachments)

    // ── shouldNotify: viewed/focused matrix ────────────────────────────────────────

    @Test fun notifies_when_the_session_is_not_the_selected_one() {
        assertTrue(NotifyDecision.shouldNotify(reply(), "s1", selectedId = "s2", windowFocused = true, muted = false))
    }

    @Test fun notifies_when_nothing_is_selected_at_all() {
        assertTrue(NotifyDecision.shouldNotify(reply(), "s1", selectedId = null, windowFocused = true, muted = false))
    }

    @Test fun notifies_when_selected_but_the_window_is_unfocused() {
        assertTrue(NotifyDecision.shouldNotify(reply(), "s1", selectedId = "s1", windowFocused = false, muted = false))
    }

    @Test fun does_not_notify_when_the_session_is_selected_and_the_window_is_focused() {
        assertFalse(NotifyDecision.shouldNotify(reply(), "s1", selectedId = "s1", windowFocused = true, muted = false))
    }

    // ── shouldNotify: mute ─────────────────────────────────────────────────────────

    @Test fun does_not_notify_a_muted_session_even_when_totally_unviewed() {
        assertFalse(NotifyDecision.shouldNotify(reply(), "s1", selectedId = null, windowFocused = false, muted = true))
    }

    // ── shouldNotify: entry kind (own message / non-reply outbound) ────────────────

    @Test fun does_not_notify_the_users_own_echoed_inbound_message() {
        val own = LogEntry(id = "m2", ts = "t", direction = "inbound", op = null, text = "hello")
        assertFalse(NotifyDecision.shouldNotify(own, "s1", selectedId = "s2", windowFocused = true, muted = false))
    }

    @Test fun does_not_notify_a_react_op_entry() {
        val react = LogEntry(id = "m3", ts = "t", direction = "outbound", op = "react", text = null)
        assertFalse(NotifyDecision.shouldNotify(react, "s1", selectedId = "s2", windowFocused = true, muted = false))
    }

    @Test fun does_not_notify_an_edit_message_op_entry() {
        val edit = LogEntry(id = "m4", ts = "t", direction = "outbound", op = "edit_message", text = "edited")
        assertFalse(NotifyDecision.shouldNotify(edit, "s1", selectedId = "s2", windowFocused = true, muted = false))
    }

    @Test fun does_not_notify_an_outbound_entry_with_a_null_op() {
        // A null op is NOT "reply" — only an explicit op="reply" is a chat reply (mirrors
        // hook.ts's `action.op !== "reply"` guard, which is an equality check, not a truthiness one).
        val noOp = LogEntry(id = "m5", ts = "t", direction = "outbound", op = null, text = "??")
        assertFalse(NotifyDecision.shouldNotify(noOp, "s1", selectedId = "s2", windowFocused = true, muted = false))
    }

    // ── previewText ────────────────────────────────────────────────────────────────

    @Test fun preview_text_returns_the_reply_text_verbatim_when_short() {
        assertEquals("all done", NotifyDecision.previewText(reply(text = "all done")))
    }

    @Test fun preview_text_truncates_at_117_chars_plus_an_ellipsis() {
        val long = "x".repeat(200)
        val preview = NotifyDecision.previewText(reply(text = long))
        assertEquals(118, preview.length) // 117 chars + "…"
        assertTrue(preview.endsWith("…"))
        assertEquals("x".repeat(117), preview.dropLast(1))
    }

    @Test fun preview_text_does_not_truncate_exactly_120_chars() {
        val exact = "y".repeat(120)
        assertEquals(exact, NotifyDecision.previewText(reply(text = exact)))
    }

    @Test fun preview_text_falls_back_to_a_photo_label_when_text_is_blank() {
        val entry = reply(text = null, attachments = listOf(Attachment(file_id = "f1", kind = "photo")))
        assertEquals("📷 Photo", NotifyDecision.previewText(entry))
    }

    @Test fun preview_text_falls_back_to_a_named_document_label() {
        val entry = reply(text = "", attachments = listOf(Attachment(file_id = "f1", kind = "document", name = "report.pdf")))
        assertEquals("📎 report.pdf", NotifyDecision.previewText(entry))
    }

    @Test fun preview_text_falls_back_to_new_message_with_no_text_or_attachments() {
        val entry = reply(text = null, attachments = null)
        assertEquals("New message", NotifyDecision.previewText(entry))
    }

    // ── NotificationDedup ──────────────────────────────────────────────────────────

    @Test fun dedup_allows_the_first_fire_for_a_session() {
        val dedup = NotificationDedup(windowMs = 4_000)
        assertTrue(dedup.shouldFire("s1", nowMs = 1_000))
    }

    @Test fun dedup_suppresses_a_second_fire_within_the_window() {
        val dedup = NotificationDedup(windowMs = 4_000)
        assertTrue(dedup.shouldFire("s1", nowMs = 1_000))
        assertFalse(dedup.shouldFire("s1", nowMs = 2_000)) // 1s later, inside the 4s window
    }

    @Test fun dedup_allows_a_fire_right_after_the_window_elapses() {
        val dedup = NotificationDedup(windowMs = 4_000)
        assertTrue(dedup.shouldFire("s1", nowMs = 1_000))
        assertTrue(dedup.shouldFire("s1", nowMs = 5_001))
    }

    @Test fun dedup_windows_are_independent_per_session() {
        val dedup = NotificationDedup(windowMs = 4_000)
        assertTrue(dedup.shouldFire("s1", nowMs = 1_000))
        assertTrue(dedup.shouldFire("s2", nowMs = 1_100)) // different session, not suppressed
    }

    @Test fun clear_resets_a_sessions_cooldown_immediately() {
        val dedup = NotificationDedup(windowMs = 4_000)
        assertTrue(dedup.shouldFire("s1", nowMs = 1_000))
        dedup.clear("s1")
        assertTrue(dedup.shouldFire("s1", nowMs = 1_100)) // would otherwise still be suppressed
    }

    @Test fun clear_of_an_unknown_session_is_a_no_op() {
        val dedup = NotificationDedup()
        dedup.clear("never-fired") // must not throw
    }
}
