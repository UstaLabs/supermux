package dev.supermux.net

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL-fixture test: decode the captured `rfb-zrle-session.bin` (an actual x11vnc
 * RFB byte stream from the live broker) end-to-end — handshake header parse +
 * ServerInit + the first ZRLE FramebufferUpdate — and assert the decoded BGRA
 * frame matches the values cross-checked by the Python reference decoder
 * (see commonTest/resources/README.md).
 */
class RfbFixtureJvmTest {
    private fun loadFixture(name: String): ByteArray {
        // Prefer the classpath (commonTest resources are merged into the jvm test
        // runtime); fall back to the source tree for robustness.
        javaClass.getResourceAsStream("/$name")?.use { return it.readBytes() }
        val candidates = listOf(
            "src/commonTest/resources/$name",
            "shared/src/commonTest/resources/$name",
            "apps/shared/src/commonTest/resources/$name",
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f.readBytes()
        }
        error("fixture $name not found on classpath or source tree (cwd=${File(".").absolutePath})")
    }

    @Test fun decodes_real_zrle_session_to_expected_pixels() {
        val data = loadFixture("rfb-zrle-session.bin")

        var i = 0
        // ProtocolVersion
        assertEquals(3 to 8, RfbCodec.parseProtocolVersion(data.copyOfRange(i, i + 12))); i += 12
        // security: count + types
        val count = data[i].toInt() and 0xff; i += 1
        assertTrue(count >= 1)
        val types = data.copyOfRange(i, i + count); i += count
        assertTrue(types.any { it.toInt() == 1 }, "fixture should offer None")
        // SecurityResult
        assertEquals(0, RfbCodec.u32(data, i)); i += 4
        // ServerInit
        val si = RfbCodec.parseServerInit(data.copyOfRange(i, data.size))!!
        assertEquals(1280, si.width)
        assertEquals(800, si.height)
        i += RfbCodec.serverInitSize(si.name.encodeToByteArray().size)

        // FramebufferUpdate: type byte must be 0
        assertEquals(0, data[i].toInt() and 0xff)
        i += 1
        val zrle = ZrleDecoder()
        val parsed = FramebufferUpdate.parseBody(data, i, zrle)
            ?: error("FramebufferUpdate did not fully parse")
        assertEquals(1, parsed.rects.size)
        val rect = parsed.rects[0]
        assertEquals(0, rect.x); assertEquals(0, rect.y)
        assertEquals(1280, rect.width); assertEquals(800, rect.height)
        assertEquals(1280 * 800 * 4, rect.bgra.size)

        fun px(x: Int, y: Int): List<Int> {
            val o = (y * 1280 + x) * 4
            return listOf(
                rect.bgra[o].toInt() and 0xff, rect.bgra[o + 1].toInt() and 0xff,
                rect.bgra[o + 2].toInt() and 0xff, rect.bgra[o + 3].toInt() and 0xff,
            )
        }
        // Golden values from the reference decoder (README.md).
        assertEquals(listOf(0, 0, 0, 255), px(0, 0))
        assertEquals(listOf(95, 58, 31, 255), px(640, 400))
        assertEquals(listOf(48, 193, 0, 255), px(200, 150))
        assertEquals(listOf(242, 48, 48, 255), px(75, 75))
        assertEquals(listOf(95, 58, 31, 255), px(1279, 799))

        // Whole-frame checksum cross-check with the reference decoder.
        val sha = MessageDigest.getInstance("SHA-256").digest(rect.bgra)
        val hex = sha.joinToString("") { "%02x".format(it) }
        assertTrue(hex.startsWith("bd52e7d8fd6de8fc"), "frame sha256 mismatch: $hex")
    }

    @Test fun golden_client_handshake_matches_encoders() {
        val client = loadFixture("rfb-client-handshake.bin")
        val rebuilt = RfbCodec.protocolVersionReply() +
            byteArrayOf(1) +
            RfbCodec.encodeClientInit(true) +
            RfbCodec.encodeSetPixelFormat() +
            RfbCodec.encodeSetEncodings() +
            RfbCodec.encodeFramebufferUpdateRequest(false, 0, 0, 1280, 800)
        assertContentEquals(client, rebuilt)
    }
}
