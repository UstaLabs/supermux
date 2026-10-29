package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BundlePathTest {
    @Test fun aPlainPathLosesItsLeadingSlash() = assertEquals("index.html", normalizeBundlePath("/index.html"))

    @Test fun queryAndFragmentAreDropped() = assertEquals("a/b.js", normalizeBundlePath("/a/b.js?v=2#top"))

    @Test fun theRootIsEmpty() {
        assertEquals("", normalizeBundlePath("/"))
        assertEquals("", normalizeBundlePath(""))
        assertEquals("", normalizeBundlePath("/?x=1"))
    }

    @Test fun emptyAndDotSegmentsAreDropped() = assertEquals("a/b/c.css", normalizeBundlePath("/a//./b/c.css"))

    @Test fun escapesAreDecodedOnceAsUtf8() {
        assertEquals("a b.css", normalizeBundlePath("/a%20b.css"))
        assertEquals("€.txt", normalizeBundlePath("/%E2%82%AC.txt"))
        assertEquals("ö/ü.js", normalizeBundlePath("/%c3%b6/%C3%BC.js"))
    }

    @Test fun anEncodedDotDotIsRefused() {
        assertNull(normalizeBundlePath("/%2e%2e%2fsecret"))
        assertNull(normalizeBundlePath("/%2E%2E/secret"))
        assertNull(normalizeBundlePath("/a/%2e%2e/b"))
    }

    @Test fun aRawDotDotIsRefusedNotResolved() {
        assertNull(normalizeBundlePath("/a/../b"))
        assertNull(normalizeBundlePath("/.."))
    }

    @Test fun doubleEncodingIsRefused() {
        assertNull(normalizeBundlePath("/%252e%252e/x"))
        assertNull(normalizeBundlePath("/100%25.txt"))
    }

    @Test fun backslashesAreRefusedRawOrEncoded() {
        assertNull(normalizeBundlePath("/..%5c..%5cwindows"))
        assertNull(normalizeBundlePath("/a%5Cb"))
        assertNull(normalizeBundlePath("/a\\b"))
    }

    @Test fun nulAndControlCharactersAreRefused() {
        assertNull(normalizeBundlePath("/index.html%00.png"))
        assertNull(normalizeBundlePath("/a%0Ab"))
        assertNull(normalizeBundlePath("/a%7F"))
    }

    @Test fun malformedEscapesAndBrokenUtf8AreRefused() {
        assertNull(normalizeBundlePath("/%zz"))
        assertNull(normalizeBundlePath("/%2"))
        assertNull(normalizeBundlePath("/a%"))
        assertNull(normalizeBundlePath("/%E2%82"))
    }

    @Test fun rawNonAsciiIsRefused() = assertNull(normalizeBundlePath("/ö.js"))
}
