package dev.supermux.android.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM unit tests for [PushRouter] — the bootstrap-vs-sealed branch + payload parse.
 *
 * Contract (relay `data.d`): a bootstrap is the *plaintext* JSON
 * `{"kind":"bootstrap","routingToken":"..."}` → register with broker; anything else is a
 * sealed blob → decrypt.
 */
class PushRouterTest {

    @Test fun bootstrap_json_routes_to_broker_register() {
        val routed = PushRouter.classify("""{"kind":"bootstrap","routingToken":"rt-123"}""")
        assertTrue(routed is PushRouter.Routed.Bootstrap, "expected Bootstrap, got $routed")
        assertEquals("rt-123", routed.routingToken)
    }

    @Test fun bootstrap_with_extra_fields_still_parses() {
        // Relay/broker may add fields; ignoreUnknownKeys must tolerate them.
        val routed = PushRouter.classify("""{"kind":"bootstrap","routingToken":"x","v":2}""")
        assertEquals("x", (routed as PushRouter.Routed.Bootstrap).routingToken)
    }

    @Test fun sealed_blob_routes_to_decrypt() {
        // A real 4-part sealed blob is dot-joined base64url — not JSON → Sealed.
        val blob =
            "BIuHSzJkvnA75BjS6-oItVYAKeBkhFTgRHUXennzhhEzcWIe-7NeVgxWnASamS1LSpJEevgjZDj5kOH5z7a0OEg" +
                ".TywyZgnf6UjxfZCtDpwKQQ.-GckVXLd8jQSf2h4.7CF8JlOIcibcSri0Z_fUUuhn"
        val routed = PushRouter.classify(blob)
        assertTrue(routed is PushRouter.Routed.Sealed, "expected Sealed, got $routed")
        assertEquals(blob, routed.blob)
    }

    @Test fun non_bootstrap_json_routes_to_decrypt() {
        // JSON that isn't a bootstrap is treated as a (to-be-decrypted) Sealed payload.
        val routed = PushRouter.classify("""{"kind":"other","foo":"bar"}""")
        assertTrue(routed is PushRouter.Routed.Sealed, "non-bootstrap JSON must be Sealed, got $routed")
    }

    @Test fun bootstrap_without_routingToken_is_not_bootstrap() {
        assertNull(PushRouter.parseBootstrap("""{"kind":"bootstrap"}"""))
        val routed = PushRouter.classify("""{"kind":"bootstrap"}""")
        assertTrue(routed is PushRouter.Routed.Sealed, "missing routingToken → Sealed")
    }

    @Test fun bootstrap_with_blank_routingToken_is_not_bootstrap() {
        assertNull(PushRouter.parseBootstrap("""{"kind":"bootstrap","routingToken":""}"""))
    }

    @Test fun parseNotification_extracts_session_sessionId_text() {
        val note = PushRouter.parseNotification(
            """{"session":"travel-assistant","sessionId":"s-9","text":"flights booked","ts":"2026-06-23T04:00:00Z"}""",
        )
        assertEquals("travel-assistant", note?.session)
        assertEquals("s-9", note?.sessionId)
        assertEquals("flights booked", note?.text)
    }

    @Test fun parseNotification_allows_missing_sessionId() {
        val note = PushRouter.parseNotification("""{"session":"chat","text":"hi","ts":"t"}""")
        assertEquals("chat", note?.session)
        assertNull(note?.sessionId)
        assertEquals("hi", note?.text)
    }

    @Test fun parseNotification_falls_back_to_media_label_when_no_text() {
        val note = PushRouter.parseNotification("""{"session":"chat","kind":"photo","ts":"t"}""")
        assertEquals("Sent a photo", note?.text)
    }

    @Test fun parseNotification_returns_null_without_session() {
        assertNull(PushRouter.parseNotification("""{"text":"orphan"}"""))
        assertNull(PushRouter.parseNotification("not json at all"))
    }

    @Test fun matches_real_broker_payload_shape() {
        // The exact plaintext the broker's sealForDevice fixture decrypts to (jvmTest source of truth).
        val plaintext =
            """{"session":"travel-assistant","text":"Agent finished: flights booked","ts":"2026-06-23T04:00:00Z"}"""
        val note = PushRouter.parseNotification(plaintext)
        assertEquals("travel-assistant", note?.session)
        assertEquals("Agent finished: flights booked", note?.text)
        assertNull(note?.sessionId)
    }
}
