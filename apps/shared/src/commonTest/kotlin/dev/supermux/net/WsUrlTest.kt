package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals

class WsUrlTest {
    @Test fun httpsToWss() = assertEquals("wss://host:9898", wsBaseUrl("https://host:9898"))
    @Test fun httpToWs() = assertEquals("ws://host:9898", wsBaseUrl("http://host:9898"))
    @Test fun wssUnchanged() = assertEquals("wss://host", wsBaseUrl("wss://host"))
    @Test fun wsUnchanged() = assertEquals("ws://host", wsBaseUrl("ws://host"))

    /** Only the leading scheme is rewritten, not a "http" later in the path. */
    @Test fun onlyLeadingScheme() = assertEquals("wss://h/p?u=http://x", wsBaseUrl("https://h/p?u=http://x"))
}
