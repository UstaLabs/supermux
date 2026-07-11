package dev.supermux.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A well-formed 26-char base32 hostId (spec §3.1).
private const val HID = "amsfen6cjgpd7pjqw6nimlahvy"

class PairingPayloadTest {
    @Test fun parsesValidV1() {
        val p = PairingPayload.parse("""{"v":1,"action":"pair","hostId":"$HID","name":"box","relayUrl":"https://h-$HID.relay.supermux.dev","claimSecret":"s3cret"}""")
        assertNotNull(p); assertEquals(HID, p.hostId); assertEquals("s3cret", p.claimSecret)
    }
    @Test fun rejectsWrongVersion() { assertNull(PairingPayload.parse("""{"v":2,"action":"pair","hostId":"$HID","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsWrongAction() { assertNull(PairingPayload.parse("""{"v":1,"action":"nope","hostId":"$HID","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsMalformedHostId() {
        // too short / not base32 — the old check only required nonblank
        assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"h","name":"b","claimSecret":"s"}"""))
        assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"UPPER1NOTBASE32AAAAAAAAAAAA","name":"b","claimSecret":"s"}"""))
    }
    @Test fun rejectsNonSupermuxRelayOrigin() { assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"$HID","name":"b","relayUrl":"https://evil.example.com","claimSecret":"s"}""")) }
    @Test fun rejectsRelaySubstringInPathAttack() {
        // the substring check would have PASSED this; the origin parse rejects it
        assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"$HID","name":"b","relayUrl":"https://evil.example.com/.relay.supermux.dev","claimSecret":"s"}"""))
    }
    @Test fun rejectsGarbage() { assertNull(PairingPayload.parse("not json")) }
    @Test fun acceptsDirectUrlOnlyPayload() {
        val p = PairingPayload.parse("""{"v":1,"action":"pair","hostId":"$HID","name":"b","directUrl":"http://100.x:9898","claimSecret":"s"}""")
        assertNotNull(p); assertEquals("http://100.x:9898", p.directUrl)
    }

    @Test fun originParserAcceptsRealRelayHostsOnly() {
        assertTrue(PairingPayload.isSupermuxRelayOrigin("https://h-$HID.relay.supermux.dev"))
        assertTrue(PairingPayload.isSupermuxRelayOrigin("https://relay.supermux.dev/x"))
        assertFalse(PairingPayload.isSupermuxRelayOrigin("https://evil.com/.relay.supermux.dev"))
        assertFalse(PairingPayload.isSupermuxRelayOrigin("https://relay.supermux.dev.evil.com"))
        assertFalse(PairingPayload.isSupermuxRelayOrigin("https://evil.com?x=.relay.supermux.dev"))
    }
}
