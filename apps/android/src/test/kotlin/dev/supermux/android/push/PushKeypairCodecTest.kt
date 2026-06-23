package dev.supermux.android.push

import dev.supermux.push.openSealedPush
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pure-JVM unit tests for [PushKeypairCodec] — the framework-free half of [PushKeypair].
 * Verifies the wire formats the broker depends on:
 *   - public key = 65-byte uncompressed P-256 point (`0x04||X||Y`), base64url-no-pad
 *   - private key = parseable PKCS#8 base64 (the input to [openSealedPush])
 */
class PushKeypairCodecTest {

    @Test fun publicKey_decodes_to_65_bytes_starting_0x04() {
        val pair = PushKeypairCodec.generate()
        // base64url-no-pad must round-trip through the URL decoder.
        val raw = Base64.getUrlDecoder().decode(pair.publicB64Url)
        assertEquals(65, raw.size, "uncompressed P-256 point must be 65 bytes")
        assertEquals(0x04, raw[0].toInt() and 0xFF, "must start with the 0x04 uncompressed prefix")
    }

    @Test fun publicKey_b64url_has_no_padding_and_is_url_safe() {
        val pub = PushKeypairCodec.generate().publicB64Url
        assertTrue(!pub.contains('='), "b64url must be unpadded: $pub")
        assertTrue(!pub.contains('+') && !pub.contains('/'), "b64url must be URL-safe: $pub")
    }

    @Test fun privateKey_is_parseable_pkcs8_base64() {
        val priv = PushKeypairCodec.generate().privatePkcs8B64
        // Standard base64 (padded) — decodes and imports as an EC private key.
        assertTrue(PushKeypairCodec.isParseablePkcs8(priv), "private key must import as PKCS#8 EC key")
    }

    @Test fun generate_produces_distinct_keypairs() {
        val a = PushKeypairCodec.generate()
        val b = PushKeypairCodec.generate()
        assertTrue(a.privatePkcs8B64 != b.privatePkcs8B64, "each generate() must be a fresh key")
        assertTrue(a.publicB64Url != b.publicB64Url, "each generate() must be a fresh pubkey")
    }

    @Test fun generated_private_key_works_with_openSealedPush_against_broker_blob() {
        // Sanity: a key we generate is in the exact PKCS#8-base64 form openSealedPush() consumes.
        // (We can't decrypt the broker's fixture with OUR key, but feeding our key in must fail
        //  with a *decrypt* error — never a key-parse error — proving the format is accepted.)
        val priv = PushKeypairCodec.generate().privatePkcs8B64
        val brokerBlob =
            "BIuHSzJkvnA75BjS6-oItVYAKeBkhFTgRHUXennzhhEzcWIe-7NeVgxWnASamS1LSpJEevgjZDj5kOH5z7a0OEg" +
                ".TywyZgnf6UjxfZCtDpwKQQ.-GckVXLd8jQSf2h4." +
                "7CF8JlOIcibcSri0Z_fUUuhnjnhLoYH3-EF72FCCuhQXU7-Uv20V5CqsQoI7Vm1raFKikk0pVG-GVpLnXbAk4lKBhg_rCkhHWqjdJ1rN_8H2Ojn32saCgnTdtCguta_gQNlEF1S_Gi-wVet6T71u4f0y"
        try {
            openSealedPush(brokerBlob, priv)
            fail("expected GCM auth/decrypt failure with a non-matching key")
        } catch (e: Throwable) {
            val msg = (e.message ?: "").lowercase()
            // Must NOT be a key-parsing failure — that would mean our PKCS#8 b64 is malformed.
            assertTrue(
                !msg.contains("pkcs8") && !msg.contains("invalidkey") && !msg.contains("invalid key"),
                "our private key should parse fine; failure must be in decryption, got: ${e.message}",
            )
        }
    }
}
