package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientInfoTest {
    @Test fun queryHasOnlyPresentFields() {
        assertEquals(
            "?clientSurface=android&clientVersion=0.14.0&clientOs=android&clientOsMajor=16",
            ClientInfo("android", "0.14.0", "android", 16).toQuery(),
        )
        assertEquals("?clientSurface=web", ClientInfo("web").toQuery())
        assertEquals("", null.toQuery())
    }
}
