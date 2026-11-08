package dev.supermux.webview.engine.android

import dev.supermux.webview.WebContent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SavedStateCodecTest {
    private val payload = byteArrayOf(1, 2, 3, 0, -1)

    @Test fun stateComesBackForItsOwnContentOnly() {
        val state = SavedStateCodec.encode("url:https://a.test/", payload)
        assertContentEquals(payload, SavedStateCodec.decode(state, "url:https://a.test/"))
        assertNull(SavedStateCodec.decode(state, "url:https://a.test/other"))
        assertNull(SavedStateCodec.decode(state, "url:https://a.test"))
    }

    /** Two pages whose keys have the same length: only the key's bytes tell their states apart. */
    @Test fun aKeyOfTheSameLengthIsComparedByteByByte() {
        val first = WebContent.Html("<p>first page</p>").key
        val second = WebContent.Html("<p>other page</p>").key
        assertEquals(first.encodeToByteArray().size, second.encodeToByteArray().size)
        assertNotEquals(first, second)
        val state = SavedStateCodec.encode(first, payload)
        assertContentEquals(payload, SavedStateCodec.decode(state, first))
        assertNull(SavedStateCodec.decode(state, second), "another page's state")
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
