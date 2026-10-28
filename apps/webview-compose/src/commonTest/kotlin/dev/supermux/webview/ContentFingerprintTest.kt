package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentFingerprintTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun sha256MatchesTheFipsVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
        )
    }

    @Test fun textIsHashedAsUtf8IncludingCharactersOutsideTheBmp() {
        assertEquals("f0443a342c5ef54783a111b51ba56c938e474c32324d90c3a60c9c8e3a37e2d9", hex(sha256("😀".encodeToByteArray())))
    }

    @Test fun fingerprintsHashUtf16CodeUnitsAsLowercaseBase32() {
        assertEquals("osbotl52d3xrikdmbhvwvhwpoq", contentFingerprint("<p>hi</p>"))
        assertEquals("4oymiquy7qobjgx36tejs35zeq", contentFingerprint(""))
        assertEquals("2tuipjua42avln2pmabn6ywc5d", contentFingerprint("😀"))
    }

    @Test fun aLoneSurrogateGetsItsOwnFingerprint() {
        assertEquals("eyt2ueqsgv4gfde2sar7zt5uzu", contentFingerprint("a\uD800b"))
        assertEquals("i6yobn6jbsijr3ed3whcxy5jok", contentFingerprint("a?b"))
        assertEquals("5bokryxntckji2rfwmgcsi2ywx", contentFingerprint("a�b"))
    }
}
