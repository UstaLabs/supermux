package dev.supermux.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTtsTest {
    @Test
    fun plainTextStripsFencedCode() {
        val out = plainTextForSpeech("Hello\n\n```ts\nconst x = 1\n```\n\nworld")
        assertTrue(out.contains("Hello"))
        assertTrue(out.contains("world"))
        assertFalse(out.contains("const x"))
    }

    @Test
    fun plainTextUnwrapsLinksAndInlineCode() {
        assertEquals("See docs and foo.", plainTextForSpeech("See [docs](https://x.test) and `foo`."))
    }

    @Test
    fun plainTextEmpty() {
        assertEquals("", plainTextForSpeech(""))
        assertEquals("", plainTextForSpeech("   "))
    }
}
