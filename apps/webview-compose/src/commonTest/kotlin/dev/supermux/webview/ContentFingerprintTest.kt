package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentFingerprintTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun sha256MatchesTheFipsVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
        )
    }

    @Test fun textIsHashedAsUtf8IncludingCharactersOutsideTheBmp() {
        assertEquals("f0443a342c5ef54783a111b51ba56c938e474c32324d90c3a60c9c8e3a37e2d9", hex(sha256("😀".encodeToByteArray())))
    }

    @Test fun fingerprintsHashUtf16CodeUnitsAsLowercaseBase32() {
        assertEquals("osbotl52d3xrikdmbhvwvhwpoq", contentFingerprint("<p>hi</p>"))
        assertEquals("4oymiquy7qobjgx36tejs35zeq", contentFingerprint(""))
        assertEquals("2tuipjua42avln2pmabn6ywc5d", contentFingerprint("😀"))
    }

    @Test fun aLoneSurrogateGetsItsOwnFingerprint() {
        assertEquals("eyt2ueqsgv4gfde2sar7zt5uzu", contentFingerprint("a\uD800b"))
        assertEquals("i6yobn6jbsijr3ed3whcxy5jok", contentFingerprint("a?b"))
        assertEquals("5bokryxntckji2rfwmgcsi2ywx", contentFingerprint("a�b"))
    }

    /** What hashes the texts, counting them: a test sees which ones were hashed again. */
    private class Counting {
        val hashed = mutableListOf<String>()

        fun hash(text: String): String = contentFingerprint(text).also { hashed += text }
    }

    /**
     * A text hashed once isn't hashed again, as the same string or an equal one: a list item that builds its content as
     * it scrolls in would otherwise hash the whole HTML at each scroll-in, on the main thread.
     */
    @Test fun aTextHashedOnceIsNotHashedAgain() {
        val counting = Counting()
        val fingerprints = RecentFingerprints(hash = counting::hash)
        val text = "<p>" + "x".repeat(1_000) + "</p>"
        val first = fingerprints.of(text)
        assertEquals(contentFingerprint(text), first)
        assertEquals(first, fingerprints.of(text))
        assertEquals(first, fingerprints.of(text.toCharArray().concatToString()), "the same text in another string")
        assertEquals(1, counting.hashed.size)
    }

    /**
     * Only the latest texts are kept, so many and so many characters of them, and none longer than that: what it holds
     * of texts the app has let go of stays a few MB.
     */
    @Test fun recentFingerprintsKeepSoManyTextsAndCharactersAtMost() {
        val counting = Counting()
        val fingerprints = RecentFingerprints(maxEntries = 3, maxChars = 100, hash = counting::hash)
        val texts = List(4) { "t$it" }
        texts.forEach { fingerprints.of(it) }
        fingerprints.of(texts[3])
        fingerprints.of(texts[0])
        assertEquals(texts + texts[0], counting.hashed, "the oldest of four goes, the latest stays")
        val big = "b".repeat(60)
        val bigger = "c".repeat(60)
        fingerprints.of(big)
        fingerprints.of(bigger)
        fingerprints.of(big)
        assertEquals(listOf(big, bigger, big), counting.hashed.takeLast(3), "120 characters don't fit in 100")
        val huge = "h".repeat(101)
        fingerprints.of(huge)
        fingerprints.of(huge)
        assertEquals(2, counting.hashed.count { it == huge }, "a text longer than all it keeps was kept")
    }

    /** [WebContent.Html] hashes through the shared one: built again for its text, it hashes nothing. */
    @Test fun anHtmlContentBuiltAgainForItsTextIsNotHashedAgain() {
        val text = "<p>built again, then again</p>"
        val first = WebContent.Html(text)
        assertTrue(text in RecentFingerprints.Shared, "its fingerprint wasn't kept")
        assertEquals(first.key, WebContent.Html(text).key)
    }
}
