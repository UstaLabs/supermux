package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * How far two pictures of the same size are apart, over every red, green and blue value of every pixel, 0 to 255:
 * the mean, the 99th percentile and the most. The quality bar for a picture of a page is a mean of at most 2 and a 99th
 * percentile of at most 16.
 */
internal class PictureDifference(val mean: Double, val p99: Int, val most: Int) {
    /** Whether it is within the quality bar. */
    val looksTheSame: Boolean get() = mean <= 2.0 && p99 <= 16

    override fun toString(): String = "mean ${"%.2f".format(mean)}, p99 $p99, max $most"

    companion object {
        fun of(a: Bitmap, b: Bitmap): PictureDifference {
            require(a.width == b.width && a.height == b.height) {
                "${a.width}x${a.height} against ${b.width}x${b.height}"
            }
            val pa = pixels(a)
            val pb = pixels(b)
            val counts = LongArray(256)
            var sum = 0L
            for (i in pa.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs((pa[i] shr shift and 0xFF) - (pb[i] shr shift and 0xFF))
                    counts[d]++
                    sum += d
                }
            }
            val total = 3L * pa.size
            var seen = 0L
            var p99 = 255
            for (d in 0..255) {
                seen += counts[d]
                if (seen >= total * 0.99) {
                    p99 = d
                    break
                }
            }
            val most = (255 downTo 0).first { counts[it] > 0 || it == 0 }
            return PictureDifference(sum.toDouble() / total, p99, most)
        }

        fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}
