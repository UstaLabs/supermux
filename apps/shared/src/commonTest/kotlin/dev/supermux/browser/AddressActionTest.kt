package dev.supermux.browser

import dev.supermux.net.ProxyDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressActionTest {
    private val preview = ProxyDto(domain = "p-5173", sessionName = "fix-login", port = 5173, url = "https://relay.test/p/p-5173/")
    private val byPort = "https://relay.test/p/{port}/"

    @Test fun aSiteLoads() {
        assertEquals(AddressAction.Load("https://vitejs.dev"), addressAction("vitejs.dev", emptyList(), onHost = false, byPort = byPort))
    }

    @Test fun aPortIsTheHostsOwnOnTheHost() {
        assertEquals(AddressAction.Load("http://localhost:5173/login"), addressAction("localhost:5173/login", emptyList(), onHost = true, byPort = byPort))
    }

    @Test fun aPortElsewhereOpensThroughTheHostsByPortPreview() {
        assertEquals(AddressAction.Load("https://relay.test/p/5173/login"), addressAction("http://localhost:5173/login", listOf(preview), onHost = false, byPort = byPort))
    }

    @Test fun withoutByPortANamedPreviewOfThePortOpens() {
        assertEquals(AddressAction.Load("https://relay.test/p/p-5173/login"), addressAction("http://localhost:5173/login", listOf(preview), onHost = false, byPort = null))
    }

    @Test fun aPortWithNoRouteSaysSo() {
        val said = addressAction("3000", listOf(preview), onHost = false, byPort = null)
        assertTrue(said is AddressAction.Say && "port 3000" in said.message && "can't be opened" in said.message)
    }

    @Test fun textThatIsntAnAddressSaysSo() {
        val said = addressAction("how do I deploy", emptyList(), onHost = false, byPort = byPort)
        assertTrue(said is AddressAction.Say && "isn't an address" in said.message)
    }
}
