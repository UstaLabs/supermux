package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RfbCodecTest {
    // ── protocol version ──────────────────────────────────────────────────────
    @Test fun parses_protocol_version() {
        assertEquals(3 to 8, RfbCodec.parseProtocolVersion("RFB 003.008\n".encodeToByteArray()))
        assertEquals(3 to 3, RfbCodec.parseProtocolVersion("RFB 003.003\n".encodeToByteArray()))
    }

    @Test fun protocol_version_reply_is_3_8() {
        assertContentEquals("RFB 003.008\n".encodeToByteArray(), RfbCodec.protocolVersionReply())
        assertEquals(12, RfbCodec.protocolVersionReply().size)
    }

    // ── ClientInit ────────────────────────────────────────────────────────────
    @Test fun client_init_shared() {
        assertContentEquals(byteArrayOf(1), RfbCodec.encodeClientInit(shared = true))
        assertContentEquals(byteArrayOf(0), RfbCodec.encodeClientInit(shared = false))
    }

    // ── SetPixelFormat golden bytes (32bpp BGRA) ──────────────────────────────
    @Test fun set_pixel_format_golden() {
        // type(0) pad(3) | bpp32 depth24 be0 tc1 | rmax255 gmax255 bmax255 | rsh16 gsh8 bsh0 | pad(3)
        val golden = intArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0)
            .map { it.toByte() }.toByteArray()
        assertContentEquals(golden, RfbCodec.encodeSetPixelFormat())
    }

    // ── SetEncodings golden bytes ([16,1,0,-223]) ─────────────────────────────
    @Test fun set_encodings_golden() {
        // type(2) pad(0) count(0,4) | 16 | 1 | 0 | -223=0xFFFFFF21
        val golden = intArrayOf(2, 0, 0, 4, 0, 0, 0, 16, 0, 0, 0, 1, 0, 0, 0, 0, 255, 255, 255, 33)
            .map { it.toByte() }.toByteArray()
        assertContentEquals(golden, RfbCodec.encodeSetEncodings(intArrayOf(16, 1, 0, -223)))
        assertContentEquals(golden, RfbCodec.encodeSetEncodings()) // default list matches
    }

    // ── FramebufferUpdateRequest ──────────────────────────────────────────────
    @Test fun fbur_full_and_incremental() {
        val full = RfbCodec.encodeFramebufferUpdateRequest(false, 0, 0, 0x0500, 0x0320)
        assertContentEquals(
            intArrayOf(3, 0, 0, 0, 0, 0, 5, 0, 3, 0x20).map { it.toByte() }.toByteArray(), full,
        )
        val inc = RfbCodec.encodeFramebufferUpdateRequest(true, 0, 0, 1280, 800)
        assertEquals(1, inc[1].toInt())
    }

    // ── input encoders ────────────────────────────────────────────────────────
    @Test fun pointer_event_6_bytes() {
        // mask 0x01, x=0x0102, y=0x0304
        assertContentEquals(
            intArrayOf(5, 1, 1, 2, 3, 4).map { it.toByte() }.toByteArray(),
            RfbCodec.encodePointerEvent(0x01, 0x0102, 0x0304),
        )
    }

    @Test fun key_event_8_bytes() {
        // down, keysym 0xFF0D (Return)
        assertContentEquals(
            intArrayOf(4, 1, 0, 0, 0, 0, 0xFF, 0x0D).map { it.toByte() }.toByteArray(),
            RfbCodec.encodeKeyEvent(0xFF0DL, true),
        )
        assertEquals(0, RfbCodec.encodeKeyEvent(0xFF0DL, false)[1].toInt())
    }

    // ── ServerInit parse ──────────────────────────────────────────────────────
    @Test fun parse_server_init() {
        val name = "ustalabs:100"
        val nb = name.encodeToByteArray()
        val buf = ByteArray(24 + nb.size)
        RfbCodec.putU16(buf, 0, 1280)
        RfbCodec.putU16(buf, 2, 800)
        // pixel format bytes 4..19 arbitrary
        RfbCodec.putU32(buf, 20, nb.size.toLong())
        nb.copyInto(buf, 24)
        val si = RfbCodec.parseServerInit(buf)!!
        assertEquals(1280, si.width)
        assertEquals(800, si.height)
        assertEquals(name, si.name)
        // too-short returns null
        assertNull(RfbCodec.parseServerInit(buf.copyOf(10)))
    }

    // ── bit reversal ──────────────────────────────────────────────────────────
    @Test fun reverse_bits() {
        assertEquals(0x80, RfbCodec.reverseBits(0x01))
        assertEquals(0x01, RfbCodec.reverseBits(0x80))
        assertEquals(0xFF, RfbCodec.reverseBits(0xFF))
        assertEquals(0x00, RfbCodec.reverseBits(0x00))
        assertEquals(0b0101_0101, RfbCodec.reverseBits(0b1010_1010))
    }

    // ── golden client→server handshake equals the captured fixture ────────────
    // (the bytes our capture-rfb.ts sent, byte-for-byte, are the encoder goldens)
    @Test fun golden_handshake_segments_match_capture() {
        // version reply + sec pick(1) + clientInit(1) + SetPixelFormat + SetEncodings + FBUR(full)
        val parts = RfbCodec.protocolVersionReply() +
            byteArrayOf(1) +
            RfbCodec.encodeClientInit(true) +
            RfbCodec.encodeSetPixelFormat() +
            RfbCodec.encodeSetEncodings() +
            RfbCodec.encodeFramebufferUpdateRequest(false, 0, 0, 1280, 800)
        // 12 + 1 + 1 + 20 + 20 + 10 = 64 (matches rfb-client-handshake.bin length)
        assertEquals(64, parts.size)
        val expected = intArrayOf(
            82, 70, 66, 32, 48, 48, 51, 46, 48, 48, 56, 10, // "RFB 003.008\n"
            1, // None
            1, // ClientInit shared
            0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0, // SetPixelFormat
            2, 0, 0, 4, 0, 0, 0, 16, 0, 0, 0, 1, 0, 0, 0, 0, 255, 255, 255, 33,  // SetEncodings
            3, 0, 0, 0, 0, 0, 5, 0, 3, 32, // FBUR full 1280x800
        ).map { it.toByte() }.toByteArray()
        assertContentEquals(expected, parts)
    }

    // ── VNC-Auth DES known-answer test ────────────────────────────────────────
    // Published DES test vector (FIPS): key 133457799BBCDFF1, pt 0123456789ABCDEF
    //                                   → ct 85E813540F0AB405.
    @Test fun des_known_answer() {
        val key = hex("133457799BBCDFF1")
        val pt = hex("0123456789ABCDEF")
        val out = ByteArray(8)
        Des(key).encryptBlock(pt, 0, out, 0)
        assertContentEquals(hex("85E813540F0AB405"), out)
    }

    // VNC-Auth uses the password as a DES key with EVERY key byte bit-reversed.
    // Cross-check: encrypt with `encodeVncAuthResponse(challenge, pw)` and compare
    // to encrypting the same challenge with a DES whose key is the manually
    // bit-reversed UTF-8 password bytes (zero-padded to 8). Both halves must match.
    @Test fun vnc_auth_response_uses_bit_reversed_key() {
        val pw = "secret12" // 8 ASCII bytes
        val pwBytes = pw.encodeToByteArray()
        val reversedKey = ByteArray(8) { RfbCodec.reverseBits(pwBytes[it].toInt() and 0xff).toByte() }
        val ref = Des(reversedKey)

        val challenge = ByteArray(16) { (it * 7 + 3).toByte() } // arbitrary 16-byte challenge
        val expected = ByteArray(16)
        ref.encryptBlock(challenge, 0, expected, 0)
        ref.encryptBlock(challenge, 8, expected, 8)

        val resp = RfbCodec.encodeVncAuthResponse(challenge, pw)
        assertEquals(16, resp.size)
        assertContentEquals(expected, resp)

        // Sanity: a NON-reversed key would (almost certainly) differ.
        val plain = ByteArray(16)
        val plainDes = Des(ByteArray(8) { pwBytes[it] })
        plainDes.encryptBlock(challenge, 0, plain, 0)
        plainDes.encryptBlock(challenge, 8, plain, 8)
        assertTrue(!plain.contentEquals(resp), "bit-reversed key must differ from raw key")
    }

    @Test fun vnc_auth_pads_short_password() {
        // a 3-char password must not throw; produces a 16-byte response
        val resp = RfbCodec.encodeVncAuthResponse(ByteArray(16) { it.toByte() }, "abc")
        assertEquals(16, resp.size)
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
