package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairUrlTest {
    @Test fun parsesLegacyDirectPairUrl() {
        assertEquals(
            PairUrl("ws://192.168.1.20:9898", "old-token"),
            PairUrl.parse("http://192.168.1.20:9898/pair?t=old-token"),
        )
    }

    @Test fun parsesLegacyHttpsPairUrl() {
        assertEquals(
            PairUrl("wss://old-host.example", "old-token"),
            PairUrl.parse("https://old-host.example/pair?t=old-token"),
        )
    }

    @Test fun parsesDeepLinkWithEmbeddedDirectBase() {
        assertEquals(
            PairUrl("ws://192.168.1.20:9898", "old-token"),
            PairUrl.parse("supermux://pair?t=old-token&base=http%3A%2F%2F192.168.1.20%3A9898"),
        )
    }

    @Test fun rejectsUrlWithoutToken() {
        assertNull(PairUrl.parse("http://192.168.1.20:9898/pair"))
    }
}
