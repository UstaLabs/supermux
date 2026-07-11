package dev.supermux.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingPayloadTest {
    @Test fun parsesValidV1() {
        val p = PairingPayload.parse("""{"v":1,"action":"pair","hostId":"habc","name":"box","relayUrl":"https://h-habc.relay.supermux.dev","claimSecret":"s3cret"}""")
        assertNotNull(p); assertEquals("habc", p.hostId); assertEquals("s3cret", p.claimSecret)
    }
    @Test fun rejectsWrongVersion() { assertNull(PairingPayload.parse("""{"v":2,"action":"pair","hostId":"h","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsWrongAction() { assertNull(PairingPayload.parse("""{"v":1,"action":"nope","hostId":"h","name":"b","claimSecret":"s"}""")) }
    @Test fun rejectsNonSupermuxRelayOrigin() { assertNull(PairingPayload.parse("""{"v":1,"action":"pair","hostId":"h","name":"b","relayUrl":"https://evil.example.com","claimSecret":"s"}""")) }
    @Test fun rejectsGarbage() { assertNull(PairingPayload.parse("not json")) }
    @Test fun acceptsDirectUrlOnlyPayload() {
        val p = PairingPayload.parse("""{"v":1,"action":"pair","hostId":"h","name":"b","directUrl":"http://100.x:9898","claimSecret":"s"}""")
        assertNotNull(p); assertEquals("http://100.x:9898", p.directUrl)
    }
}
