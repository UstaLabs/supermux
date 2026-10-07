package dev.supermux.ui.widgets.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The proof that the hand-written encoder in `QrEncoder.kt` is spec-correct: every matrix it
 * produces is handed to ZXing's `QRCodeReader` — a completely independent implementation — and must
 * come back as the exact text that went in.
 *
 * That is the whole reason ZXing survives in `jvmTest` after leaving commonMain (it is a JVM-only
 * jar, and `:ui` now compiles for iOS). A self-consistency test would prove nothing: an encoder
 * that gets the Reed-Solomon field, the interleave order, the zig-zag walk or the mask scoring
 * wrong is perfectly self-consistent and completely unscannable.
 *
 * Coverage is spread across what changes the CODE PATH, not just the text: every version band (the
 * 8-bit vs 16-bit character count at v10, the version-information block from v7, multi-block
 * interleaving, the largest symbol there is), unicode (the ECI segment), and the real pairing-link
 * shape the app actually encodes.
 */
class QrEncoderTest {

    /** Decode a bare module matrix (no scaling, no quiet zone) with ZXing. */
    private fun decode(matrix: QrMatrix): String {
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i ->
            if (matrix[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    private fun roundTrip(text: String) {
        val matrix = QrEncoder.encode(text)
        assertEquals(text, decode(matrix), "round trip failed for ${text.length} chars")
    }

    // -- Payload spread ----------------------------------------------------------------------

    @Test fun roundTripsShortText() = roundTrip("hello-supermux")

    @Test fun roundTripsASingleCharacter() = roundTrip("x")

    /** The shape the app actually encodes: a pairing link with a 40-character token. */
    @Test fun roundTripsARealPairingLink() {
        val token = "k7Qm2Xr9Tb4Vz8Nc1Hd6Jf3Lg5Ps0Wy7Ae2Bu4Cx"
        assertEquals(40, token.length)
        roundTrip("https://pair.supermux.dev/pair?t=$token&h=9f2c7ab1&v=1")
    }

    /** ~300 characters: a relay pairing URL with every optional parameter present. */
    @Test fun roundTripsA300CharacterLink() {
        val link = buildString {
            append("https://relay.supermux.dev/pair?t=")
            append("q".repeat(80))
            append("&host=")
            append("h".repeat(80))
            append("&name=Ahmet%27s%20MacBook%20Pro&relay=eu-west&v=1&sig=")
            append("f".repeat(64))
        }
        assertTrue(link.length in 280..320, "expected ~300 chars, got ${link.length}")
        roundTrip(link)
    }

    /**
     * Unicode: accents, CJK, an emoji (4-byte UTF-8) and a check mark. Byte mode's DEFAULT
     * interpretation is ISO-8859-1, so this only round-trips because the encoder emits the ECI 26
     * (UTF-8) segment ahead of the data - drop that and this test comes back mojibake.
     */
    @Test fun roundTripsUnicode() = roundTrip("héllo — 世界 🚀 süpermüx ✓ café")

    /** JSON with quotes, braces and backslashes: the other payload shape (TOFU pairing claims). */
    @Test fun roundTripsJsonWithEscapes() =
        roundTrip("{\"v\":1,\"action\":\"pair\",\"name\":\"A \\\"quoted\\\" host\",\"secret\":\"s3cr3t\"}")

    // -- Version bands -----------------------------------------------------------------------

    /**
     * Every version 1-40 encodes and decodes at its own maximum payload.
     *
     * This is the test that actually exercises the tables: each version's EC-block count and parity
     * length, the 8-to-16-bit character-count switch at version 10, the version-information block
     * from version 7, multi-block interleaving, and the alignment-pattern coordinates (including
     * the version-32 case the even-spacing formula gets wrong).
     */
    @Test fun roundTripsEveryVersionAtItsMaximumPayload() {
        for (version in 1..40) {
            val capacity = QrEncoder.byteCapacity(version, QrEcLevel.MEDIUM)
            val text = buildString { for (i in 0 until capacity) append('a' + (i % 26)) }
            val matrix = QrEncoder.encode(text)
            assertEquals(version * 4 + 17, matrix.width, "version $version picked the wrong size")
            assertEquals(text, decode(matrix), "version $version failed to round trip")
        }
    }

    /** The largest payload a QR code can carry at EC-M, byte mode: version 40. */
    @Test fun roundTripsTheLargestPossiblePayload() {
        val capacity = QrEncoder.byteCapacity(40, QrEcLevel.MEDIUM)
        assertEquals(2330, capacity, "version 40 EC-M byte capacity (2331 less the 12-bit ECI header)")
        val text = (1..capacity).joinToString("") { ('0' + (it % 10)).toString() }
        val matrix = QrEncoder.encode(text)
        assertEquals(177, matrix.width)
        assertEquals(text, decode(matrix))
    }

    @Test fun rejectsAPayloadLargerThanVersion40() {
        val tooBig = "z".repeat(QrEncoder.byteCapacity(40, QrEcLevel.MEDIUM) + 1)
        assertFailsWith<QrCapacityException> { QrEncoder.encode(tooBig) }
    }

    // -- Version selection -------------------------------------------------------------------

    /** The smallest version that fits is chosen: one byte more steps up exactly one version. */
    @Test fun picksTheSmallestVersionThatFits() {
        for (version in 1..39) {
            val capacity = QrEncoder.byteCapacity(version, QrEcLevel.MEDIUM)
            assertEquals(version, QrEncoder.smallestVersionFor(capacity, QrEcLevel.MEDIUM))
            assertEquals(version + 1, QrEncoder.smallestVersionFor(capacity + 1, QrEcLevel.MEDIUM))
        }
    }

    /**
     * The EC tables against ISO/IEC 18004's published byte-mode capacities at EC-M, for every one of
     * the forty versions. A transcription slip in `ECC_CODEWORDS_PER_BLOCK` / `NUM_EC_BLOCKS` shows
     * up HERE, as a wrong number, rather than years later as a symbol some scanner cannot read.
     *
     * It also pins `rawDataModules`, which is a FORMULA rather than a table: the two have to agree
     * for all forty versions or one of them is wrong.
     */
    @Test fun capacitiesMatchTheStandardsTable() {
        // ISO/IEC 18004 table 7, byte mode, EC level M — all forty versions, in order.
        val published = intArrayOf(
            14, 26, 42, 62, 84, 106, 122, 152, 180, 213,
            251, 287, 331, 362, 412, 450, 504, 560, 624, 666,
            711, 779, 857, 911, 997, 1059, 1125, 1190, 1264, 1370,
            1452, 1538, 1628, 1722, 1809, 1911, 1989, 2099, 2213, 2331,
        )
        for ((index, standard) in published.withIndex()) {
            val version = index + 1
            val dataBits = QrEncoder.dataCodewords(version, QrEcLevel.MEDIUM) * 8
            val countBits = if (version <= 9) 8 else 16
            assertEquals(
                standard,
                (dataBits - 4 - countBits) / 8,
                "version $version data codewords disagree with the standard's byte capacity",
            )
            // What this encoder actually offers is that, less the always-emitted 12-bit ECI header.
            assertEquals(
                (dataBits - 12 - 4 - countBits) / 8,
                QrEncoder.byteCapacity(version, QrEcLevel.MEDIUM),
            )
        }
    }

    // -- Format information ------------------------------------------------------------------

    /** The symbol says EC level M - the level this app has always used, read back off the wire. */
    @Test fun encodesErrorCorrectionLevelM() {
        val matrix = QrEncoder.encode("ec-level-check")
        val w = matrix.width
        val pixels = IntArray(w * w) { i ->
            if (matrix[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val result = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels))),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )
        assertEquals("M", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL].toString())
    }

    /** All four EC levels encode and decode: the format bits and the block tables agree for each. */
    @Test fun roundTripsEveryErrorCorrectionLevel() {
        for (ec in QrEcLevel.entries) {
            val text = "ec-${ec.name}-payload"
            assertEquals(text, decode(QrEncoder.encodeBytes(text.encodeToByteArray(), ec)), "EC $ec")
        }
    }

    // -- Reed-Solomon ------------------------------------------------------------------------

    /**
     * GF(256) arithmetic against hand-computable values: the field is x^8+x^4+x^3+x^2+1 (0x11D), so
     * 2*2 = 4, 2*0x80 reduces to 0x1D, 0 annihilates and 1 is the identity.
     */
    @Test fun gf256MultiplicationReduces() {
        assertEquals(0, ReedSolomon.multiply(0, 0xFF))
        assertEquals(0xFF, ReedSolomon.multiply(1, 0xFF))
        assertEquals(4, ReedSolomon.multiply(2, 2))
        assertEquals(0x1D, ReedSolomon.multiply(2, 0x80))
    }

    /** Parity is `degree` bytes, deterministic, and a real checksum (one changed byte changes it). */
    @Test fun parityIsDeterministicAndSensitive() {
        for (degree in intArrayOf(7, 10, 13, 15, 16, 17, 18, 20, 22, 24, 26, 28, 30)) {
            assertEquals(degree, ReedSolomon.generator(degree).size)
        }
        val data = ByteArray(16) { it.toByte() }
        val first = ReedSolomon.parity(data, 10)
        assertEquals(10, first.size)
        assertTrue(first.contentEquals(ReedSolomon.parity(data, 10)))
        val changed = data.copyOf().also { it[3] = 0x7F }
        assertTrue(!first.contentEquals(ReedSolomon.parity(changed, 10)), "parity ignored a changed byte")
    }

    // -- Geometry ----------------------------------------------------------------------------

    /**
     * Alignment-pattern coordinates against ISO/IEC 18004 annex E for the versions that pin the
     * formula's edges: the first version that has any (2), the first with three (7), the
     * special-cased 32, and the largest (40).
     */
    @Test fun alignmentPatternPositionsMatchTheStandard() {
        assertTrue(QrSymbol(1).alignmentPositions().isEmpty())
        assertEquals(listOf(6, 18), QrSymbol(2).alignmentPositions().toList())
        assertEquals(listOf(6, 22, 38), QrSymbol(7).alignmentPositions().toList())
        assertEquals(listOf(6, 30, 58, 86, 114, 142, 170), QrSymbol(40).alignmentPositions().toList())
        // Version 32 is the one the even-spacing formula gets wrong (it yields 28, not 26).
        assertEquals(listOf(6, 34, 60, 86, 112, 138), QrSymbol(32).alignmentPositions().toList())
    }

    /** Finder patterns sit in three corners; the fourth is free, which is the orientation cue. */
    @Test fun finderPatternsOccupyThreeCorners() {
        val m = QrEncoder.encode("finders")
        val n = m.width
        for ((cx, cy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val ring = dx == 0 || dx == 6 || dy == 0 || dy == 6
                    val core = dx in 2..4 && dy in 2..4
                    assertEquals(ring || core, m[cx + dx, cy + dy], "finder $cx,$cy module $dx,$dy")
                }
            }
        }
    }

    /** Timing patterns: alternating modules along row 6 and column 6. */
    @Test fun timingPatternsAlternate() {
        val m = QrEncoder.encode("timing")
        for (i in 8 until m.width - 8) {
            assertEquals(i % 2 == 0, m[i, 6], "horizontal timing at $i")
            assertEquals(i % 2 == 0, m[6, i], "vertical timing at $i")
        }
    }

    /** The dark module: always dark, in every symbol, at (8, 4*version+9). */
    @Test fun theDarkModuleIsAlwaysDark() {
        for (text in listOf("a", "b".repeat(200), "c".repeat(1200))) {
            val m = QrEncoder.encode(text)
            assertTrue(m[8, m.height - 8], "dark module missing in a ${m.width}-module symbol")
        }
    }

    // -- Masking -----------------------------------------------------------------------------

    /**
     * Mask selection does its job: an all-one-character payload lays down long uniform runs before
     * masking, and the chosen mask still leaves a dark ratio near half. Without mask selection an
     * encoder emits large solid areas no scanner locks onto.
     */
    @Test fun maskingKeepsTheDarkRatioNearHalf() {
        for (text in listOf("A".repeat(400), " ".repeat(300), "0".repeat(1000))) {
            val m = QrEncoder.encode(text)
            var dark = 0
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) dark++
            val ratio = dark.toDouble() / (m.width * m.height)
            assertTrue(ratio in 0.40..0.60, "dark ratio $ratio for a ${m.width}-module symbol")
            assertEquals(text, decode(m))
        }
    }

    /**
     * All 8 mask patterns produce a decodable symbol, so every branch of `applyMask` and its format
     * bits are sound - not just whichever one the penalty scorer happens to pick.
     */
    @Test fun everyMaskPatternIsDecodable() {
        val text = "mask-pattern-coverage-payload"
        for (mask in 0..7) {
            val symbol = QrSymbol(4)
            symbol.drawFunctionPatterns(QrEcLevel.MEDIUM)
            symbol.drawCodewords(QrEncoder.codewordsFor(text, 4, QrEcLevel.MEDIUM))
            symbol.applyMask(mask)
            symbol.drawFormatBits(QrEcLevel.MEDIUM, mask)
            assertEquals(text, decode(symbol.toMatrix()), "mask $mask")
        }
    }

    /** The penalty scorer prefers the masked symbol to an unmasked one for a uniform payload. */
    @Test fun penaltyScoringPrefersAMaskedSymbol() {
        val text = "Z".repeat(120)
        val bare = QrSymbol(7)
        bare.drawFunctionPatterns(QrEcLevel.MEDIUM)
        bare.drawCodewords(QrEncoder.codewordsFor(text, 7, QrEcLevel.MEDIUM))
        val unmasked = bare.penaltyScore()

        val masked = QrSymbol(7)
        masked.drawFunctionPatterns(QrEcLevel.MEDIUM)
        masked.drawCodewords(QrEncoder.codewordsFor(text, 7, QrEcLevel.MEDIUM))
        masked.applyBestMask(QrEcLevel.MEDIUM)
        assertTrue(
            masked.penaltyScore() < unmasked,
            "masking made it worse: ${masked.penaltyScore()} >= $unmasked",
        )
    }
}
