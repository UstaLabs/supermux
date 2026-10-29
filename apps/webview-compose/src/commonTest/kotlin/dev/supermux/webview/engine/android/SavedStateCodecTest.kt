package dev.supermux.webview.engine.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SavedStateCodecTest {
    private val payload = byteArrayOf(1, 2, 3, 0, -1)

    @Test fun stateComesBackForItsOwnContentOnly() {
        val state = SavedStateCodec.encode("url:https://a.test/", payload)
        assertContentEquals(payload, SavedStateCodec.decode(state, "url:https://a.test/"))
        assertNull(SavedStateCodec.decode(state, "url:https://a.test/other"))
        assertNull(SavedStateCodec.decode(state, "url:https://a.test"))
    }

    @Test fun anEmptyPayloadRoundTrips() {
        assertContentEquals(byteArrayOf(), SavedStateCodec.decode(SavedStateCodec.encode("k", byteArrayOf()), "k"))
    }

    @Test fun anythingElseIsRefused() {
        val state = SavedStateCodec.encode("k", payload)
        assertNull(SavedStateCodec.decode(byteArrayOf(), "k"))
        assertNull(SavedStateCodec.decode("fake:k".encodeToByteArray(), "k"), "another engine's state")
        assertNull(SavedStateCodec.decode(state.copyOf(state.size - payload.size - 1), "k"), "cut inside the key")
        val otherVersion = state.copyOf().also { it[17] = 2 }
        assertNull(SavedStateCodec.decode(otherVersion, "k"))
        val hugeKey = state.copyOf().also { it[18] = 0x7f }
        assertNull(SavedStateCodec.decode(hugeKey, "k"))
    }
}
