package dev.supermux.ui

import dev.supermux.ui.DiffLineKind.ADD
import dev.supermux.ui.DiffLineKind.CONTEXT
import dev.supermux.ui.DiffLineKind.META
import dev.supermux.ui.DiffLineKind.REMOVE
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDiffDocTest {
    @Test
    fun synthesizedBrokerDiff_dropsHeaders_andPrefixes() {
        val doc = toolDiffDoc("--- a/x.kt\n+++ b/x.kt\n@@ -1,1 +1,2 @@\n-val a = 1\n+val a = 2\n+val b = 3")
        assertEquals("val a = 1\nval a = 2\nval b = 3", doc.text)
        assertEquals(listOf(REMOVE, ADD, ADD), doc.kinds)
    }

    @Test
    fun laterHunkHeaders_stayAsMeta() {
        val doc = toolDiffDoc("@@ -1,2 +1,2 @@\n a\n-b\n+c\n@@ -10,1 +10,1 @@ fun f()\n-x\n+y\n")
        assertEquals("a\nb\nc\n@@ -10,1 +10,1 @@ fun f()\nx\ny", doc.text)
        assertEquals(listOf(CONTEXT, REMOVE, ADD, META, REMOVE, ADD), doc.kinds)
    }

    @Test
    fun removedSqlComment_insideAHunk_isNotAFileHeader() {
        val doc = toolDiffDoc("@@ -1 +1 @@\n--- old note\n+++ new note")
        assertEquals("-- old note\n++ new note", doc.text)
        assertEquals(listOf(REMOVE, ADD), doc.kinds)
    }

    @Test
    fun codexMultiFile_keepsTheFileHeader_andSkipsItsPatchHeaders() {
        val doc = toolDiffDoc(
            "update a.ts\n@@ -1 +1 @@\n-a\n+b\n\nupdate b.ts\n--- a/b.ts\n+++ b/b.ts\n@@ -1 +1 @@\n-c\n+d",
        )
        assertEquals(listOf("update a.ts", "a", "b", "", "update b.ts", "c", "d"), doc.text.split('\n'))
        assertEquals(listOf(META, REMOVE, ADD, CONTEXT, META, REMOVE, ADD), doc.kinds)
    }

    @Test
    fun noNewlineMarker_andCrlf_areDropped() {
        val doc = toolDiffDoc("@@ -1 +1 @@\r\n-a\r\n\\ No newline at end of file\r\n+b\r\n")
        assertEquals("a\nb", doc.text)
        assertEquals(listOf(REMOVE, ADD), doc.kinds)
    }
}
