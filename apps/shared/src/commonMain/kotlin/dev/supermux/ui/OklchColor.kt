package dev.supermux.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Convert an OKLCH colour (L 0..1, C chroma, H degrees) to packed 0xAARRGGBB (opaque). */
fun oklchToArgb(l: Double, c: Double, hDeg: Double): Int {
    val h = hDeg * PI / 180.0
    val aLab = c * cos(h)
    val bLab = c * sin(h)
    val l_ = l + 0.3963377774 * aLab + 0.2158037573 * bLab
    val m_ = l - 0.1055613458 * aLab - 0.0638541728 * bLab
    val s_ = l - 0.0894841775 * aLab - 1.2914855480 * bLab
    val lc = l_ * l_ * l_; val mc = m_ * m_ * m_; val sc = s_ * s_ * s_
    val r = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc
    val g = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc
    val b = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc
    fun gamma(x: Double): Int {
        val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    }
    return (0xFF shl 24) or (gamma(r) shl 16) or (gamma(g) shl 8) or gamma(b)
}
