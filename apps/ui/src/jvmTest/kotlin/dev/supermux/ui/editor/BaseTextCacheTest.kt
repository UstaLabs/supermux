package dev.supermux.ui.editor

import dev.supermux.net.BlobText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseTextCacheTest {
    @Test fun a_text_is_fetched_once_per_blob() = runTest {
        var calls = 0
        val cache = BaseTextCache(maxChars = 1000)
        val fetch: suspend (String, String, Boolean) -> BlobText = { _, _, _ -> calls++; BlobText.Text("abc") }
        assertEquals(BlobText.Text("abc"), cache.get("", "a".repeat(40), false, fetch))
        assertEquals(BlobText.Text("abc"), cache.get("", "a".repeat(40), false, fetch))
        assertEquals(1, calls)
    }

    @Test fun failures_are_not_cached() = runTest {
        var calls = 0
        val cache = BaseTextCache(maxChars = 1000)
        val fetch: suspend (String, String, Boolean) -> BlobText = { _, _, _ -> calls++; BlobText.Failed("x") }
        cache.get("", "a".repeat(40), false, fetch)
        cache.get("", "a".repeat(40), false, fetch)
        assertEquals(2, calls)
    }

    @Test fun the_oldest_text_is_evicted_past_the_budget() = runTest {
        var calls = 0
        val cache = BaseTextCache(maxChars = 5)
        val fetch: suspend (String, String, Boolean) -> BlobText = { _, sha, _ -> calls++; BlobText.Text(sha.take(3)) }
        cache.get("", "a".repeat(40), false, fetch)
        cache.get("", "b".repeat(40), false, fetch)   // 6 chars > 5: "aaa" goes
        cache.get("", "a".repeat(40), false, fetch)
        assertEquals(3, calls)
    }

    @Test fun hunk_header_names_the_hunk_holding_the_line() {
        val base = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n"
        val working = "1\n2\n3\n4\nFIVE\n6\n7\n8\n9\n10\n"
        assertEquals("@@ -2,7 +2,7 @@", hunkHeaderAt(base, working, 5))
        assertEquals("", hunkHeaderAt(base, base, 5))
    }

    @Test fun hunk_header_at_the_first_line() {
        val base = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n"
        val working = "ONE\n2\n3\n4\n5\n6\n7\n8\n9\n10\n"
        assertEquals("@@ -1,4 +1,4 @@", hunkHeaderAt(base, working, 1))
    }

    @Test fun hunk_header_at_the_last_line() {
        val base = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n"
        val working = "1\n2\n3\n4\n5\n6\n7\n8\n9\nTEN\n"
        assertEquals("@@ -7,4 +7,4 @@", hunkHeaderAt(base, working, 10))
    }

    @Test fun eviction_is_lru_not_fifo() = runTest {
        var calls = 0
        val cache = BaseTextCache(maxChars = 6)
        val fetch: suspend (String, String, Boolean) -> BlobText = { _, sha, _ -> calls++; BlobText.Text(sha.take(3)) }
        cache.get("", "a".repeat(40), false, fetch)
        cache.get("", "b".repeat(40), false, fetch)
        cache.get("", "a".repeat(40), false, fetch)   // hit: a is now most recent
        assertEquals(2, calls)
        cache.get("", "c".repeat(40), false, fetch)   // 9 > 6: b goes, a stays
        assertEquals(3, calls)
        cache.get("", "a".repeat(40), false, fetch)
        assertEquals(3, calls)
        cache.get("", "b".repeat(40), false, fetch)
        assertEquals(4, calls)
    }

    @Test fun hunk_header_for_a_new_file() {
        assertEquals("@@ -0,0 +1,2 @@", hunkHeaderAt("", "x\ny\n", 1))
    }

    @Test fun hunk_header_for_a_deleted_file() {
        assertEquals("@@ -1,2 +0,0 @@", hunkHeaderAt("x\ny\n", "", 1))
    }

    @Test fun a_line_between_two_hunks_picks_the_nearest_hunk_above() {
        val nums = (1..30).map { it.toString() }
        val base = nums.joinToString("\n") + "\n"
        val working = nums.mapIndexed { i, s -> if (i == 1 || i == 24) "X$s" else s }.joinToString("\n") + "\n"
        assertEquals("@@ -1,5 +1,5 @@", hunkHeaderAt(base, working, 12))
        assertEquals("@@ -22,7 +22,7 @@", hunkHeaderAt(base, working, 25))
    }

    @Test fun hunk_header_for_a_pure_deletion() {
        val base = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n"
        val working = "1\n2\n3\n4\n6\n7\n8\n9\n10\n"
        assertEquals("@@ -2,7 +2,6 @@", hunkHeaderAt(base, working, 5))
    }
}
