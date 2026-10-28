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

    @Test fun originsAreSchemeAndHostWithDefaultPortsDropped() {
        assertEquals("https://a.test", originOf("https://user:pw@A.test:443/x?y"))
        assertEquals("http://a.test:8080", originOf("http://a.test:8080/"))
        assertEquals("https://c1.invalid", originOf("https://c1.invalid/page"))
        assertEquals("null", originOf("about:blank"))
        assertEquals("null", originOf("data:text/html,x"))
        assertEquals("null", originOf("not a url"))
    }
}
