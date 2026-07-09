package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The push ordering / echo-skip / reveal-queue-until-ready state machine (pure, no KCEF). */
class EditorPushPlannerTest {

    private fun planner(wrap: Boolean = false, size: Int = 13) = EditorPushPlanner(wrap, size)

    @Test
    fun set_document_before_ready_emits_nothing_but_records_state() {
        val p = planner()
        assertEquals(emptyList(), p.setDocument("hello", "a.kt"))
        assertEquals("hello", p.initContent())
        assertEquals("a.kt", p.initFilename())
    }

    @Test
    fun on_ready_flushes_the_queued_document_in_push_order() {
        val p = planner(wrap = true, size = 15)
        p.setDocument("hello", "a.kt", scrollTop = 40)
        val js = p.onReady()
        assertEquals(
            listOf(
                "cmSetContent(\"hello\")",
                "cmSetLanguage(\"a.kt\")",
                "cmSetLineWrap(true)",
                "cmSetFontSize(15)",
                "cmSetScrollTop(40)",
            ),
            js,
        )
        assertTrue(p.ready)
    }

    @Test
    fun reveal_before_ready_is_queued_then_flushed_after_the_document_on_ready() {
        val p = planner()
        p.setDocument("l1\nl2\nl3", "a.kt")
        assertEquals(emptyList(), p.revealLine(3, null)) // queued, not ready
        val js = p.onReady()
        // Reveal rides at the END of the push (after scrollTop) so it wins over the scroll reset.
        assertEquals("cmRevealLine(3, -1)", js.last())
    }

    @Test
    fun reveal_after_ready_emits_immediately() {
        val p = planner()
        p.setDocument("x", "a.kt")
        p.onReady()
        assertEquals(listOf("cmRevealLine(10, 20)"), p.revealLine(10, 20))
    }

    @Test
    fun same_file_content_change_after_ready_pushes_only_the_text() {
        val p = planner()
        p.setDocument("v1", "a.kt")
        p.onReady()
        assertEquals(listOf("cmSetContent(\"v2\")"), p.setDocument("v2", "a.kt"))
    }

    @Test
    fun path_change_after_ready_pushes_the_full_document() {
        val p = planner()
        p.setDocument("v1", "a.kt")
        p.onReady()
        val js = p.setDocument("other", "b.py", scrollTop = 5)
        assertEquals(
            listOf(
                "cmSetContent(\"other\")",
                "cmSetLanguage(\"b.py\")",
                "cmSetLineWrap(false)",
                "cmSetFontSize(13)",
                "cmSetScrollTop(5)",
            ),
            js,
        )
    }

    @Test
    fun echo_of_our_own_edit_is_skipped() {
        val p = planner()
        p.setDocument("v1", "a.kt")
        p.onReady()
        // User types → onChange("v1x") → recordEcho BEFORE Compose round-trips it back to setDocument.
        p.recordEcho("v1x")
        assertEquals(emptyList(), p.setDocument("v1x", "a.kt"), "our own echo should not re-push")
        // A genuine out-of-band change (different from the echo) still pushes.
        assertEquals(listOf("cmSetContent(\"v2\")"), p.setDocument("v2", "a.kt"))
    }

    @Test
    fun font_size_is_clamped_and_only_emits_when_ready() {
        val p = planner()
        assertEquals(emptyList(), p.setFontSize(18)) // not ready
        assertEquals(18, p.fontSize)
        p.onReady()
        assertEquals(listOf("cmSetFontSize(24)"), p.setFontSize(99)) // clamped to max
        assertEquals(listOf("cmSetFontSize(10)"), p.setFontSize(1)) // clamped to min
    }

    @Test
    fun user_font_size_updates_state_without_emitting() {
        val p = planner()
        p.onReady()
        assertEquals(20, p.recordUserFontSize(20))
        // Next full push carries the user's size.
        val js = p.setDocument("x", "b.py")
        assertTrue(js.contains("cmSetFontSize(20)"))
    }

    @Test
    fun set_line_wrap_updates_state_and_emits_when_ready() {
        val p = planner(wrap = false)
        assertEquals(emptyList(), p.setLineWrap(true)) // not ready yet
        assertTrue(p.lineWrap)
        p.onReady()
        assertEquals(listOf("cmSetLineWrap(false)"), p.setLineWrap(false))
    }

    @Test
    fun set_scroll_top_emits_only_when_ready() {
        val p = planner()
        assertEquals(emptyList(), p.setScrollTop(100))
        p.onReady()
        assertEquals(listOf("cmSetScrollTop(250)"), p.setScrollTop(250))
    }
}
