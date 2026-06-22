package dev.supermux.net

/**
 * Minimal pure-Kotlin DES (ECB, single 8-byte block) — just enough for VNC-Auth,
 * which DES-encrypts the 16-byte challenge in two 8-byte halves. No padding, no
 * decryption. commonMain can't use `javax.crypto`, so this is hand-rolled from
 * the FIPS 46-3 tables and validated against the published DES test vector
 * (key 0x133457799BBCDFF1, plaintext 0x0123456789ABCDEF →
 *  ciphertext 0x85E813540F0AB405).
 */
internal class Des(key: ByteArray) {
    private val subkeys: Array<LongArray> = run {
        require(key.size == 8) { "DES key must be 8 bytes" }
        val k = bytesToLong(key, 0)
        generateSubkeys(k)
    }

    /** Encrypt one 8-byte block from [src]@[srcOff] into [dst]@[dstOff]. */
    fun encryptBlock(src: ByteArray, srcOff: Int, dst: ByteArray, dstOff: Int) {
        val block = bytesToLong(src, srcOff)
        val out = crypt(block)
        longToBytes(out, dst, dstOff)
    }

    private fun crypt(input: Long): Long {
        // Initial permutation
        var ip = permute(input, IP, 64)
        var left = (ip ushr 32) and 0xFFFFFFFFL
        var right = ip and 0xFFFFFFFFL
        for (round in 0 until 16) {
            val prevRight = right
            right = left xor feistel(right, subkeys[round])
            left = prevRight
        }
        // Note the pre-output swap: combine as R16 L16.
        val preOutput = (right shl 32) or (left and 0xFFFFFFFFL)
        return permute(preOutput, FP, 64)
    }

    private fun feistel(right: Long, subkey: LongArray): Long {
        // Expand 32 → 48 bits
        val expanded = permute(right, E, 32)
        // 48-bit subkey is stored as a single Long in subkey[0]
        val x = expanded xor subkey[0]
        // 8 S-boxes: each takes 6 bits → 4 bits
        var output = 0L
        for (i in 0 until 8) {
            val six = ((x ushr (42 - 6 * i)) and 0x3F).toInt()
            val row = ((six and 0x20) ushr 4) or (six and 0x01)
            val col = (six ushr 1) and 0x0F
            val sval = SBOXES[i][row * 16 + col].toLong()
            output = (output shl 4) or sval
        }
        // P permutation of the 32-bit S-box output
        return permute(output, P, 32)
    }

    private fun generateSubkeys(key: Long): Array<LongArray> {
        // PC-1: 64 → 56 bits
        val permuted = permute(key, PC1, 64)
        var c = (permuted ushr 28) and 0x0FFFFFFFL
        var d = permuted and 0x0FFFFFFFL
        return Array(16) { round ->
            val shift = SHIFTS[round]
            c = rotateLeft28(c, shift)
            d = rotateLeft28(d, shift)
            val cd = (c shl 28) or d
            // PC-2: 56 → 48 bits
            longArrayOf(permute(cd, PC2, 56))
        }
    }

    /**
     * Permute the low [inBits] bits of [input] according to [table] (1-based bit
     * positions counted from the MSB of the input field). Returns a value with
     * `table.size` bits, MSB-first.
     */
    private fun permute(input: Long, table: IntArray, inBits: Int): Long {
        var out = 0L
        for (pos in table) {
            val bit = (input ushr (inBits - pos)) and 1L
            out = (out shl 1) or bit
        }
        return out
    }

    private fun rotateLeft28(v: Long, n: Int): Long {
        val mask = 0x0FFFFFFFL
        return ((v shl n) or (v ushr (28 - n))) and mask
    }

    private fun bytesToLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun longToBytes(v: Long, b: ByteArray, off: Int) {
        for (i in 0 until 8) b[off + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    companion object {
        private val IP = intArrayOf(
            58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
            62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        )
        private val FP = intArrayOf(
            40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
            38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
            34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
        )
        private val E = intArrayOf(
            32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
            12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
            22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
        )
        private val P = intArrayOf(
            16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
            2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25,
        )
        private val PC1 = intArrayOf(
            57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
            10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
            63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
            14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
        )
        private val PC2 = intArrayOf(
            14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10,
            23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
            41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
            44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
        )
        private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

        // 8 S-boxes, each 4 rows × 16 cols, flattened row-major.
        private val SBOXES = arrayOf(
            intArrayOf(
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
            ),
            intArrayOf(
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
            ),
            intArrayOf(
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
            ),
            intArrayOf(
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
            ),
            intArrayOf(
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
            ),
            intArrayOf(
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
            ),
            intArrayOf(
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
            ),
            intArrayOf(
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
            ),
        )
    }
}
