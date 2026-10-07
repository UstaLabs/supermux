// A pure-Kotlin QR Code encoder, written from ISO/IEC 18004 — the ONE encoder for all three hosts.
//
// WHY it exists: `widgets/QrCode.kt` used ZXing's `QRCodeWriter`, and ZXing core is a JVM-only jar.
// The moment `:ui` gained iOS targets (cluster H1) a JVM-only artifact in commonMain stopped
// resolving at all, and the dependency policy for this phase is "no new libraries" — so the ~350
// lines of encoder below replace it. Nothing else about the QR changed: still byte mode, still EC
// level M, still rendered by `qrBitmap` into a Compose `ImageBitmap`.
//
// It ENCODES only. Scanning stays native (Android's zxing-android-embedded, iOS's AVFoundation).
//
// Proof: `QrEncoderTest` + `QrCodeTest` decode every matrix this file produces with ZXing's own
// `QRCodeReader` (ZXing survives in `jvmTest` for exactly that), across short text, a real pairing
// link, unicode, control characters, and the largest payload version 40 at EC-M can hold. A decode
// by an independent implementation is the only honest proof that a hand-written encoder is correct.
package dev.supermux.ui.widgets.qr

/**
 * A square grid of QR modules. `true` = dark.
 *
 * Deliberately shaped like the ZXing `BitMatrix` it replaced (`width`/`height`/`get(x, y)`) so the
 * call sites and their tests did not have to change with the implementation.
 */
class QrMatrix(val width: Int, val height: Int, private val cells: BooleanArray) {
    init {
        require(cells.size == width * height) { "cells ${cells.size} != $width x $height" }
    }

    operator fun get(x: Int, y: Int): Boolean =
        if (x < 0 || y < 0 || x >= width || y >= height) false else cells[y * width + x]

    internal fun setRegion(x0: Int, y0: Int, w: Int, h: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) cells[y * width + x] = true
    }
}

/** The four error-correction levels, with the 2-bit indicator the format information carries. */
enum class QrEcLevel(internal val formatBits: Int) {
    LOW(0b01), MEDIUM(0b00), QUARTILE(0b11), HIGH(0b10),
}

/** Thrown when [QrEncoder.encode] cannot fit the payload — the same failure ZXing's writer raised,
 *  which is why `qrBitmap`'s callers wrap it in `runCatching`. */
class QrCapacityException(message: String) : IllegalArgumentException(message)

/**
 * Byte-mode QR encoder: UTF-8 payload → the smallest version (1–40) that holds it at the requested
 * EC level → the masked module matrix, quiet zone NOT included.
 *
 * Byte mode only, on purpose: the payloads are pairing URLs and JSON, which numeric/alphanumeric
 * mode could not carry anyway, and a segment optimiser would be code with no caller.
 */
object QrEncoder {

    /** Encode [text] as UTF-8 bytes at [ec], choosing the smallest version that fits. */
    fun encode(text: String, ec: QrEcLevel = QrEcLevel.MEDIUM): QrMatrix =
        encodeBytes(text.encodeToByteArray(), ec)

    fun encodeBytes(data: ByteArray, ec: QrEcLevel): QrMatrix {
        val version = smallestVersionFor(data.size, ec)
            ?: throw QrCapacityException(
                "payload of ${data.size} bytes exceeds QR version 40 at EC $ec " +
                    "(max ${byteCapacity(40, ec)})",
            )
        val codewords = buildCodewords(data, version, ec)
        val symbol = QrSymbol(version)
        symbol.drawFunctionPatterns(ec)
        symbol.drawCodewords(codewords)
        symbol.applyBestMask(ec)
        return symbol.toMatrix()
    }

    /** The codeword stream for [text] at a FIXED [version] — a test seam, so `QrEncoderTest` can
     *  drive one symbol through each of the 8 masks instead of only the one scoring picks. */
    internal fun codewordsFor(text: String, version: Int, ec: QrEcLevel): ByteArray =
        buildCodewords(text.encodeToByteArray(), version, ec)

    /** The smallest version 1..40 whose byte-mode capacity at [ec] holds [byteCount], or null. */
    internal fun smallestVersionFor(byteCount: Int, ec: QrEcLevel): Int? =
        (1..40).firstOrNull { byteCount <= byteCapacity(it, ec) }

    /** How many raw bytes byte mode can carry in [version] at [ec] (the mode + count header
     *  removed). Public-ish so a test can encode the largest payload version 40 accepts. */
    fun byteCapacity(version: Int, ec: QrEcLevel): Int {
        val dataBits = dataCodewords(version, ec) * 8
        val header = ECI_HEADER_BITS + 4 + charCountBits(version)
        return (dataBits - header) / 8
    }

    // ── Bit stream ───────────────────────────────────────────────────────────────────────────
    // mode indicator (0100 = byte) · character count · the bytes · terminator · pad to a byte
    // boundary · 0xEC/0x11 alternating pad codewords. Then the codewords are split into blocks,
    // each block gets its Reed–Solomon parity, and the blocks are INTERLEAVED (which is what makes
    // a burst of damage spread across blocks rather than destroying one).

    private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    /**
     * ECI (0111) + assignment number 26 = UTF-8, 12 bits, emitted ahead of every byte segment.
     *
     * Byte mode's DEFAULT interpretation is ISO-8859-1, so without this a scanner has to GUESS that
     * a payload is UTF-8 and an accented or CJK character can come back mojibake. ZXing's writer
     * emitted exactly this segment for the `CHARACTER_SET = UTF-8` hint the old `encodeQr` passed,
     * so keeping it also keeps the produced symbols byte-identical to what shipped.
     */
    private const val ECI_HEADER_BITS = 12
    private const val ECI_UTF8 = 26

    private fun buildCodewords(data: ByteArray, version: Int, ec: QrEcLevel): ByteArray {
        val numDataCodewords = dataCodewords(version, ec)
        val bits = BitBuffer()
        bits.append(0b0111, 4) // ECI mode
        bits.append(ECI_UTF8, 8) // assignment number < 128 → a single 0xxxxxxx byte
        bits.append(0b0100, 4) // byte mode
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)

        // Terminator: up to four 0 bits, truncated if the capacity ends first.
        val capacityBits = numDataCodewords * 8
        bits.append(0, minOf(4, capacityBits - bits.size))
        // Pad to a byte boundary, then alternate the two specified pad codewords.
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            bits.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }

        return interleave(bits.toBytes(), version, ec)
    }

    /**
     * Split the data codewords into the version's blocks, append each block's RS parity, and
     * interleave: data byte i of every block in order, then parity byte i of every block.
     */
    private fun interleave(data: ByteArray, version: Int, ec: QrEcLevel): ByteArray {
        val numBlocks = ecBlocks(version, ec)
        val eccPerBlock = eccCodewordsPerBlock(version, ec)
        val rawCodewords = rawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockDataLen = rawCodewords / numBlocks - eccPerBlock

        val dataBlocks = ArrayList<ByteArray>(numBlocks)
        val eccBlocks = ArrayList<ByteArray>(numBlocks)
        var offset = 0
        for (i in 0 until numBlocks) {
            val len = shortBlockDataLen + if (i < numShortBlocks) 0 else 1
            val block = data.copyOfRange(offset, offset + len)
            offset += len
            dataBlocks.add(block)
            eccBlocks.add(ReedSolomon.parity(block, eccPerBlock))
        }

        val result = ByteArray(rawCodewords)
        var k = 0
        for (i in 0..shortBlockDataLen) {
            for (b in dataBlocks) if (i < b.size) result[k++] = b[i]
        }
        for (i in 0 until eccPerBlock) {
            for (b in eccBlocks) result[k++] = b[i]
        }
        return result
    }

    // ── Capacity tables (ISO/IEC 18004 table 9) ──────────────────────────────────────────────
    // Indexed [ecLevel][version]; index 0 is unused padding so the version reads straight through.

    private val EC_ORDER = listOf(QrEcLevel.LOW, QrEcLevel.MEDIUM, QrEcLevel.QUARTILE, QrEcLevel.HIGH)

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        //  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31  32  33  34  35  36  37  38  39  40
        intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // L
        intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28), // M
        intArrayOf(0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // Q
        intArrayOf(0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30), // H
    )

    private val NUM_EC_BLOCKS = arrayOf(
        intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25), // L
        intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49), // M
        intArrayOf(0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68), // Q
        intArrayOf(0, 1, 1, 2, 4, 4, 4, 5, 5, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81), // H
    )

    internal fun eccCodewordsPerBlock(version: Int, ec: QrEcLevel): Int =
        ECC_CODEWORDS_PER_BLOCK[EC_ORDER.indexOf(ec)][version]

    internal fun ecBlocks(version: Int, ec: QrEcLevel): Int =
        NUM_EC_BLOCKS[EC_ORDER.indexOf(ec)][version]

    /** Data codewords available in [version] at [ec] — the raw capacity minus every block's parity. */
    internal fun dataCodewords(version: Int, ec: QrEcLevel): Int =
        rawDataModules(version) / 8 - eccCodewordsPerBlock(version, ec) * ecBlocks(version, ec)

    /**
     * Modules available for data + EC in [version]: the whole symbol minus the function patterns.
     *
     * Computed rather than tabulated (the formula IS the geometry): the square, minus the three
     * finders with their separators and the format information (the constant 64/128 terms), minus
     * each alignment pattern that is not swallowed by a finder or the timing pattern, minus the two
     * 3×6 version blocks from version 7 on.
     */
    internal fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }
}

/** An MSB-first bit accumulator. Small and boring on purpose — the bit order IS the format. */
internal class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size

    /** Append the low [count] bits of [value], most significant first. */
    fun append(value: Int, count: Int) {
        require(count in 0..32) { "count $count out of range" }
        for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
    }

    fun toBytes(): ByteArray {
        require(bits.size % 8 == 0) { "bit stream is not byte-aligned (${bits.size})" }
        val out = ByteArray(bits.size / 8)
        for (i in bits.indices) {
            if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        return out
    }
}

/**
 * Reed–Solomon parity over GF(256) with the QR field-generating polynomial x⁸+x⁴+x³+x²+1 (0x11D).
 *
 * Systematic encoding: the parity bytes are the remainder of the message polynomial (shifted up by
 * `degree`) divided by the generator polynomial ∏(x − 2ⁱ). Implemented as the usual streaming
 * synthetic division over a `degree`-long window, which needs no polynomial-length allocations.
 */
internal object ReedSolomon {

    /** GF(256) multiply — Russian-peasant, reducing by 0x11D whenever the top bit falls out. */
    fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    /** Coefficients of ∏(x − 2ⁱ) for i in 0 until [degree], highest power first, monic term dropped. */
    fun generator(degree: Int): ByteArray {
        require(degree in 1..255) { "degree $degree out of range" }
        val result = ByteArray(degree)
        result[degree - 1] = 1 // start at the polynomial "1"
        var root = 1
        repeat(degree) {
            // Multiply the accumulated polynomial by (x - root), in place.
            for (i in 0 until degree) {
                result[i] = multiply(result[i].toInt() and 0xFF, root).toByte()
                if (i + 1 < degree) result[i] = (result[i].toInt() xor result[i + 1].toInt()).toByte()
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    /** The [degree] parity bytes for [data]. */
    fun parity(data: ByteArray, degree: Int): ByteArray {
        val gen = generator(degree)
        val result = ByteArray(degree)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            for (i in 0 until degree - 1) result[i] = result[i + 1]
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = (result[i].toInt() xor multiply(gen[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }
}

/**
 * One symbol under construction: the module grid plus the "this module is a function pattern"
 * grid, which is what keeps masking and the zig-zag data walk off the finders, timing and format
 * areas.
 */
internal class QrSymbol(private val version: Int) {
    val size: Int = version * 4 + 17
    private val modules = BooleanArray(size * size)
    private val isFunction = BooleanArray(size * size)

    private fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark }
    fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        set(x, y, dark)
        isFunction[y * size + x] = true
    }

    fun toMatrix(): QrMatrix = QrMatrix(size, size, modules.copyOf())

    // ── Function patterns ────────────────────────────────────────────────────────────────────

    fun drawFunctionPatterns(ec: QrEcLevel) {
        // Timing patterns: alternating modules along row 6 and column 6, the whole way across.
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        // Finder patterns (with their separators) in three corners; the fourth corner is where the
        // decoder learns the symbol's rotation.
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        // Alignment patterns at every intersection of the version's coordinates, except the three
        // that would sit on a finder.
        val positions = alignmentPositions()
        val n = positions.size
        for (i in 0 until n) {
            for (j in 0 until n) {
                val corner = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)
                if (!corner) drawAlignment(positions[i], positions[j])
            }
        }
        // Reserve the format + version areas (a placeholder mask; the real bits are written once a
        // mask has been chosen). The dark module is part of the format area's reservation.
        drawFormatBits(ec, 0)
        drawVersionBits()
    }

    /** 7×7 finder centred on ([cx], [cy]) plus its one-module light separator. */
    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunction(x, y, d != 2 && d != 4)
            }
        }
    }

    /** 5×5 alignment pattern centred on ([cx], [cy]). */
    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }
    }

    /**
     * Alignment-pattern coordinates for this version (ISO/IEC 18004 table E.1, computed).
     *
     * Always 6 and 4·version+10, with the rest spaced evenly BACKWARDS from the last one — which is
     * why the first gap can be larger than the others. Version 32 is the one case the even-spacing
     * formula gets wrong (it yields 28 where the standard says 26), so it is special-cased, exactly
     * as every conforming implementation does.
     */
    internal fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in numAlign - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    /**
     * The 15-bit format information — 2 bits of EC level + 3 bits of mask, a 10-bit BCH(15,5) check
     * over generator 0x537, the whole thing XORed with 0x5412 so it is never all-zero.
     *
     * Written TWICE (around the top-left finder, and split between the other two) so a symbol whose
     * corner is damaged is still readable.
     */
    fun drawFormatBits(ec: QrEcLevel, mask: Int) {
        val data = (ec.formatBits shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

        // First copy: down the left of the top-left finder, then right along its bottom.
        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9..14) setFunction(14 - i, 8, bit(i))

        // Second copy: along the bottom-left, then up the top-right.
        for (i in 0..7) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8..14) setFunction(8, size - 15 + i, bit(i))
        // The dark module — always dark, in every symbol.
        setFunction(8, size - 8, true)
    }

    /**
     * The 18-bit version information (version 7 and up only): 6 bits of version plus a 12-bit
     * BCH(18,6) check over generator 0x1F25, mirrored into the two 3×6 blocks beside the
     * bottom-left and top-right finders.
     */
    private fun drawVersionBits() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    // ── Data placement ───────────────────────────────────────────────────────────────────────

    /**
     * Walk the codeword bits into the symbol: two-module-wide columns from the right edge leftwards
     * (skipping the vertical timing column), alternating upwards and downwards, right module before
     * left, skipping every function module.
     *
     * Any leftover modules stay light — that is the standard's "remainder bits", which the decoder
     * ignores.
     */
    fun drawCodewords(codewords: ByteArray) {
        var i = 0 // bit index into codewords
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // the vertical timing pattern is not a data column
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y * size + x] && i < codewords.size * 8) {
                        set(x, y, ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0)
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    // ── Masking ──────────────────────────────────────────────────────────────────────────────

    /** XOR the data modules (never the function ones) with mask pattern [mask] (0..7). */
    fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> error("mask $mask out of range")
                }
                if (invert) modules[y * size + x] = !modules[y * size + x]
            }
        }
    }

    /**
     * Try all 8 masks, keep the one with the lowest penalty (ISO/IEC 18004 §8.8.2). Masking is its
     * own inverse, so each trial is applied, scored, and un-applied.
     */
    fun applyBestMask(ec: QrEcLevel) {
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(mask)
            drawFormatBits(ec, mask)
            val penalty = penaltyScore()
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                best = mask
            }
            applyMask(mask)
        }
        applyMask(best)
        drawFormatBits(ec, best)
    }

    /**
     * The four penalty rules. Lower is better; the point is to avoid module arrangements a scanner
     * mis-reads — long same-colour runs, solid blocks, anything that looks like a finder, and a
     * dark/light balance far from even.
     */
    internal fun penaltyScore(): Int {
        var result = 0

        // N1: every run of 5+ same-coloured modules in a row or column costs 3, +1 per extra module.
        for (i in 0 until size) {
            result += runPenalty { j -> get(j, i) }
            result += runPenalty { j -> get(i, j) }
        }
        // N2: every 2×2 block of one colour costs 3.
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = get(x, y)
                if (c == get(x + 1, y) && c == get(x, y + 1) && c == get(x + 1, y + 1)) result += 3
            }
        }
        // N3: every 1:1:3:1:1 finder-like pattern with four light modules on one side costs 40.
        for (i in 0 until size) {
            result += finderLikePenalty { j -> get(j, i) }
            result += finderLikePenalty { j -> get(i, j) }
        }
        // N4: 10 for every 5% the dark proportion strays from half.
        var dark = 0
        for (m in modules) if (m) dark++
        val total = size * size
        result += (kotlin.math.abs(dark * 2 - total) * 10 / total) * 10
        return result
    }

    private fun runPenalty(line: (Int) -> Boolean): Int {
        var result = 0
        var runLength = 1
        for (j in 1 until size) {
            if (line(j) == line(j - 1)) {
                runLength++
                if (runLength == 5) result += 3 else if (runLength > 5) result++
            } else {
                runLength = 1
            }
        }
        return result
    }

    /**
     * The 1:1:3:1:1 dark/light/dark×3/light/dark sequence — the finder's own proportions — with a
     * four-module light margin before or after it. Off the edge of the symbol counts as light,
     * which is what the quiet zone is.
     */
    private fun finderLikePenalty(line: (Int) -> Boolean): Int {
        var result = 0
        fun at(j: Int): Boolean = if (j < 0 || j >= size) false else line(j)
        for (j in 0..size - 7) {
            val core = at(j) && !at(j + 1) && at(j + 2) && at(j + 3) && at(j + 4) && !at(j + 5) && at(j + 6)
            if (!core) continue
            val lightBefore = (1..4).all { !at(j - it) }
            val lightAfter = (1..4).all { !at(j + 6 + it) }
            if (lightBefore || lightAfter) result += 40
        }
        return result
    }
}
