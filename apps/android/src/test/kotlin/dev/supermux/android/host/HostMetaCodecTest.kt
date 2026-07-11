package dev.supermux.android.host

import dev.supermux.host.PairedHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM unit tests for [HostMetaCodec] — the token/metadata SPLIT that backs the Android
 * [AndroidHostPersistence] (spec §3.2). Metadata (recordId/hostId/name/urls/platform/version/
 * lastSeenAt) is JSON in normal storage; each token lives in the secure store keyed by recordId.
 * The load-bearing guarantee tested here: a token NEVER leaks into the metadata blob, and a host
 * whose token is missing survives (empty token) rather than being dropped — a secure-store
 * corruption must not silently wipe the fleet. No Android framework/Context needed (mirrors
 * PushKeypairCodecTest / LauncherStateTest's framework-free style).
 */
class HostMetaCodecTest {

    private val sample = listOf(
        PairedHost(
            recordId = "r1", hostId = "habc", displayName = "MacBook",
            directUrl = "http://192.168.1.2:9898", relayUrl = "https://h-habc.relay.supermux.dev",
            token = "secret-1", platform = "macos", version = "0.11.0", lastSeenAt = 1000L,
        ),
        PairedHost(
            recordId = "r2", displayName = "This host",
            directUrl = "ws://10.0.2.2:9898", token = "secret-2",
        ),
    )

    @Test fun metadataJson_neverContainsAnyToken() {
        val json = HostMetaCodec.encodeMeta(sample)
        assertFalse(json.contains("secret-1"), "token secret-1 leaked into metadata blob: $json")
        assertFalse(json.contains("secret-2"), "token secret-2 leaked into metadata blob: $json")
        // The non-secret metadata is present.
        assertTrue(json.contains("MacBook"))
        assertTrue(json.contains("habc"))
    }

    @Test fun decode_roundTripsMetadata_andReinjectsTokens() {
        val json = HostMetaCodec.encodeMeta(sample)
        val tokens = mapOf("r1" to "secret-1", "r2" to "secret-2")
        val decoded = HostMetaCodec.decode(json) { tokens[it] }
        assertEquals(sample, decoded)
    }

    @Test fun decode_missingToken_keepsHostWithEmptyToken() {
        val json = HostMetaCodec.encodeMeta(sample)
        // r2's token is gone (secure-store entry lost); r1 still resolves.
        val decoded = HostMetaCodec.decode(json) { if (it == "r1") "secret-1" else null }
        assertEquals(2, decoded.size, "a host with a missing token must NOT be dropped")
        assertEquals("secret-1", decoded[0].token)
        assertEquals("", decoded[1].token, "missing token becomes empty, host survives for re-pair")
    }

    @Test fun decode_preservesOrder() {
        val json = HostMetaCodec.encodeMeta(sample)
        val decoded = HostMetaCodec.decode(json) { "t" }
        assertEquals(listOf("r1", "r2"), decoded.map { it.recordId })
    }

    @Test fun decode_blankOrCorruptJson_yieldsEmpty() {
        assertEquals(emptyList(), HostMetaCodec.decode(null) { "t" })
        assertEquals(emptyList(), HostMetaCodec.decode("") { "t" })
        assertEquals(emptyList(), HostMetaCodec.decode("not json at all") { "t" })
    }
}
