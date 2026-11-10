package dev.supermux.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #95: what the web's download bar may call the total, from the browser's response headers. */
class BrowserDownloadTotalTest {

    @Test fun theContentLengthIsTheTotal() {
        assertEquals(1_048_576L, browserDownloadTotal("1048576", null))
        assertEquals(42L, browserDownloadTotal(" 42 ", null))
        assertEquals(0L, browserDownloadTotal("0", null))
    }

    @Test fun aFileOverFourGigabytesKeepsItsLength() {
        assertEquals(5_000_000_000L, browserDownloadTotal("5000000000", null))
    }

    @Test fun anIdentityEncodingIsNoEncoding() {
        assertEquals(500L, browserDownloadTotal("500", "identity"))
        assertEquals(500L, browserDownloadTotal("500", " Identity "))
        assertEquals(500L, browserDownloadTotal("500", ""))
    }

    @Test fun anEncodedBodyHasNoTotal() {
        // The reader gets the decoded bytes and the length counts the encoded ones: the bar would run past 100 %.
        assertNull(browserDownloadTotal("500", "gzip"))
        assertNull(browserDownloadTotal("500", "br"))
        assertNull(browserDownloadTotal("500", "GZIP"))
    }

    @Test fun noLengthOrOneThatIsNoCountIsNoTotal() {
        assertNull(browserDownloadTotal(null, null))
        assertNull(browserDownloadTotal("", null))
        assertNull(browserDownloadTotal("abc", null))
        assertNull(browserDownloadTotal("-1", null))
        assertNull(browserDownloadTotal("12.5", null))
    }
}
