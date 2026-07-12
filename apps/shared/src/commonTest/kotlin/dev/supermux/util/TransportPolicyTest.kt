package dev.supermux.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportPolicyTest {
    private fun ok(url: String) =
        assertTrue(TransportPolicy.isPlainHttpAllowedWithoutOptIn(url), "expected allowed: $url")

    private fun needsOptIn(url: String) =
        assertFalse(TransportPolicy.isPlainHttpAllowedWithoutOptIn(url), "expected opt-in required: $url")

    @Test fun encryptedIsAlwaysAllowed() {
        ok("https://my-mac.tailnet.ts.net")
        ok("https://192.168.1.5:9898")
        ok("wss://example.com/ws")
        ok("HTTPS://EXAMPLE.COM") // scheme is case-insensitive
    }

    @Test fun plainHttpLoopbackIsAllowed() {
        ok("http://localhost:9898")
        ok("http://127.0.0.1:9898")
        ok("http://127.0.0.1")
        ok("http://127.1.2.3:9898")       // whole 127.0.0.0/8 range
        ok("http://[::1]:9898")           // IPv6 loopback literal
        ok("ws://localhost:9898/ws")
        ok("http://user@localhost:9898")  // userinfo stripped before host check
    }

    @Test fun plainHttpNonLoopbackNeedsOptIn() {
        needsOptIn("http://192.168.1.5:9898")
        needsOptIn("http://10.0.0.4:8080")
        needsOptIn("http://my-mac.tailnet.ts.net")
        needsOptIn("ws://example.com/ws")
        needsOptIn("http://localhost.evil.com:9898") // not the loopback host, just a lookalike name
    }

    @Test fun unrecognizedOrSchemelessNeedsOptIn() {
        needsOptIn("example.com")          // no scheme — normalize before trusting
        needsOptIn("ftp://example.com")
        needsOptIn("")
    }
}
