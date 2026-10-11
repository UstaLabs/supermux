package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** [DictationSegment] — everything from where the dictation started is replaced by each pass. */
class DictationSegmentTest {

    @Test fun each_pass_replaces_everything_from_the_start_point() {
        val seg = DictationSegment(0)
        var t = seg.apply("", "hello")
        t = seg.apply(t, "hello there friend")
        assertEquals("hello there friend", t)
        // A spoken edit can make it shorter.
        assertEquals("hello", seg.apply(t, "hello"))
    }

    @Test fun text_typed_before_the_dictation_is_kept_with_one_space() {
        val seg = DictationSegment(5)
        var t = seg.apply("Note:", "first words")
        assertEquals("Note: first words", t)
        t = seg.apply(t, "first words and more")
        assertEquals("Note: first words and more", t)
        assertEquals("Note: x", DictationSegment(6).apply("Note: ", "x"))
    }

    @Test fun the_region_is_what_the_field_holds_now_including_typing() {
        val seg = DictationSegment(5)
        val t = seg.apply("Note:", "one two") + " (typed)"
        assertEquals("one two (typed)", seg.region(t))
    }

    @Test fun remove_takes_back_the_dictated_stretch() {
        val seg = DictationSegment(5)
        assertEquals("Note:", seg.remove(seg.apply("Note:", "some words")))
    }
}
