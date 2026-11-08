package dev.supermux.browser

import dev.supermux.net.ProxyDto
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserAddressTest {
    private val preview = ProxyDto(domain = "p-5173", sessionName = "fix-login", port = 5173, url = "https://relay.test/p/p-5173/")

    @Test fun aBarePortOrLocalhostIsAPortOnTheHost() {
        assertEquals(AddressInput.Port(3000, "/"), parseAddress("3000"))
        assertEquals(AddressInput.Port(5173, "/login?next=%2F"), parseAddress("localhost:5173/login?next=%2F"))
        assertEquals(AddressInput.Port(8080, "/"), parseAddress("http://127.0.0.1:8080"))
        assertEquals(AddressInput.Port(80, "/x"), parseAddress("http://localhost/x"))
    }

    @Test fun anAddressGoesThereAndABareHostGetsHttps() {
        assertEquals(AddressInput.Go("https://vitejs.dev/guide/"), parseAddress("https://vitejs.dev/guide/"))
        assertEquals(AddressInput.Go("https://github.com/UstaLabs/supermux/pull/412"), parseAddress("  github.com/UstaLabs/supermux/pull/412 "))
        assertEquals(AddressInput.Go("http://example.com/"), parseAddress("http://example.com/"))
    }

    @Test fun anythingElseIsNotAnAddressAndNeverASearch() {
        for (text in listOf("", "   ", "how do I center a div", "javascript:alert(1)", "file:///etc/passwd", "70000", "0", "ftp://x.test/"))
            assertEquals(AddressInput.NotAnAddress(text.trim()), parseAddress(text), "for '$text'")
    }

    @Test fun aPortOpensOnTheHostItselfOrThroughItsPreview() {
        assertEquals(PortTarget.Go("http://localhost:5173/login"), resolvePort(5173, "/login", previews = emptyList(), onHost = true))
        assertEquals(PortTarget.Go("https://relay.test/p/p-5173/login"), resolvePort(5173, "/login", listOf(preview), onHost = false))
        assertEquals(PortTarget.Go("https://relay.test/p/p-5173/"), resolvePort(5173, "/", listOf(preview), onHost = false))
        assertEquals(PortTarget.OfferPreview(3000, "/"), resolvePort(3000, "/", listOf(preview), onHost = false))
    }

    @Test fun theAddressBarShowsAPreviewAsItsPortAndHidesTheScheme() {
        val previews = listOf(preview)
        assertEquals(DisplayAddress.Preview(5173, "/login"), displayAddress("https://relay.test/p/p-5173/login", previews))
        assertEquals(DisplayAddress.Preview(5173, "/"), displayAddress("https://relay.test/p/p-5173/", previews))
        assertEquals(DisplayAddress.Local(5173, "/login"), displayAddress("http://localhost:5173/login", previews))
        assertEquals(DisplayAddress.Web("vitejs.dev", "/guide/"), displayAddress("https://vitejs.dev/guide/", previews))
        assertEquals(DisplayAddress.Web("github.com", "/UstaLabs/supermux/pull/412"), displayAddress("https://github.com/UstaLabs/supermux/pull/412", previews))
        assertEquals(DisplayAddress.Web("example.com", "/"), displayAddress("http://example.com", previews))
    }
}
