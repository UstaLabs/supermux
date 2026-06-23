package dev.supermux.push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushDecryptInteropTest {
    // A real blob sealed by the broker's TS sealForDevice for the recipient key below.
    private val PRIV_PKCS8_B64 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgayOBC9nDHcNAfIKoA4M840iGKkdBkhhdsbbh3n1di02hRANCAASuyDQu0T+YUKmTUTnOFL8YuYe8bC1FHXDvoQKsT2xqgCvNbvFeRhktSBDFcbtDjs4f6BP4OhQHjhh0V1CQIIRH"
    private val BLOB = "BIuHSzJkvnA75BjS6-oItVYAKeBkhFTgRHUXennzhhEzcWIe-7NeVgxWnASamS1LSpJEevgjZDj5kOH5z7a0OEg.TywyZgnf6UjxfZCtDpwKQQ.-GckVXLd8jQSf2h4.7CF8JlOIcibcSri0Z_fUUuhnjnhLoYH3-EF72FCCuhQXU7-Uv20V5CqsQoI7Vm1raFKikk0pVG-GVpLnXbAk4lKBhg_rCkhHWqjdJ1rN_8H2Ojn32saCgnTdtCguta_gQNlEF1S_Gi-wVet6T71u4f0y"
    private val EXPECTED = "{\"session\":\"travel-assistant\",\"text\":\"Agent finished: flights booked\",\"ts\":\"2026-06-23T04:00:00Z\"}"

    @Test fun decryptsBrokerSealedBlob() {
        assertEquals(EXPECTED, openSealedPush(BLOB, PRIV_PKCS8_B64))
    }
}
