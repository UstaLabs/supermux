package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlParsingTest {
    @Test fun schemesAreFoundTheWayBrowsersFindThem() {
        assertEquals("javascript", schemeOf("  JavaScript:alert(1)"))
        assertEquals("javascript", schemeOf("java\nscript:alert(1)"))
        assertEquals("javascript", schemeOf("\tjavascript:x"))
        assertEquals("https", schemeOf("HTTPS://a.test"))
        assertEquals("ms-msdt", schemeOf("ms-msdt:/id"))
        assertNull(schemeOf("//evil.test"))
        assertNull(schemeOf("/x"))
        assertNull(schemeOf(""))
        assertNull(schemeOf("1http:x"))
    }

    @Test fun hostsIgnoreUserinfoPortsCaseAndBackslashes() {
        assertEquals("evil.test", hostOf("https://demo.invalid@Evil.Test:8080/x"))
        assertEquals("evil.test", hostOf("https:\\\\evil.test\\x"))
        assertEquals("evil.test", hostOf("https:evil.test"))
        assertEquals("evil.test", hostOf("https:///evil.test/x"))
        assertEquals("[::1]", hostOf("http://[::1]:80/"))
        assertNull(hostOf("mailto:a@b.test"))
        assertNull(hostOf("no scheme"))
    }

    @Test fun anAuthorityKeepsItsPortAndUserinfo() {
        assertEquals("demo.invalid:443", authorityOf("https://DEMO.invalid:443/x"))
        assertEquals("a@b.test", authorityOf("https://a@b.test/"))
    }
}
