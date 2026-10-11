package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** [DictationSegment] — cumulative live transcripts replace their own stretch of the field. */
class DictationSegmentTest {

    @Test fun each_transcript_replaces_the_previous_one() {
        val seg = DictationSegment(0)
        var t = seg.apply("", "hello")
        assertEquals("hello", t)
        t = seg.apply(t, "hello there friend")
        assertEquals("hello there friend", t)
    }

    @Test fun it_appends_after_existing_draft_text_with_one_space() {
        val seg = DictationSegment(5)
        var t = seg.apply("Note:", "first words")
        assertEquals("Note: first words", t)
        t = seg.apply(t, "first words and more")
        assertEquals("Note: first words and more", t)
    }

    @Test fun typing_after_the_segment_is_kept() {
        val seg = DictationSegment(0)
        var t = seg.apply("", "one two")
        t += " (typed)"
        t = seg.apply(t, "one two three")
        assertEquals("one two three (typed)", t)
    }

    @Test fun typing_before_the_segment_is_kept() {
        val seg = DictationSegment(0)
        var t = seg.apply("", "one two")
        t = "Hey, $t"
        t = seg.apply(t, "one two three")
        assertEquals("Hey, one two three", t)
    }

    @Test fun editing_the_dictated_words_makes_them_yours_and_new_words_follow() {
        val seg = DictationSegment(0)
        var t = seg.apply("", "the quick brown fox")
        t = "the quick red fox"                         // the user fixes a word
        t = seg.apply(t, "the quick brown fox jumps over")
        assertEquals("the quick red fox jumps over", t)
        t = seg.apply(t, "the quick brown fox jumps over the dog")
        assertEquals("the quick red fox jumps over the dog", t)
    }

    @Test fun text_typed_while_nothing_is_written_yet_comes_first() {
        val seg = DictationSegment(0)
        val t = seg.apply("typed first", "spoken")
        assertEquals("typed first spoken", t)
    }

    @Test fun remove_takes_back_only_the_dictated_words() {
        val seg = DictationSegment(5)
        var t = seg.apply("Note:", "some words")
        t += " typed"
        assertEquals("Note: typed", seg.remove(t))
    }
}
