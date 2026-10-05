package dev.supermux.desktop.host.linux

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** One `(iiay)` StatusNotifierItem pixmap: ARGB32, network (big-endian) byte order, row-major. */
class SniIcon(val width: Int, val height: Int, val argb: ByteArray)

object SniPixmap {
    /** The sizes offered in `IconPixmap`; the host picks the closest to its panel. */
    val SIZES = listOf(22, 32, 48)

    /** Pure: [img]'s pixels as ARGB32 big-endian bytes (A, R, G, B per pixel). */
    fun argb32(img: BufferedImage): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h * 4)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = img.getRGB(x, y) // non-premultiplied ARGB, whatever the image's own type
                out[i++] = (p ushr 24).toByte()
                out[i++] = (p ushr 16).toByte()
                out[i++] = (p ushr 8).toByte()
                out[i++] = p.toByte()
            }
        }
        return out
    }

    /** [src] scaled to [size]×[size] (bicubic, alpha kept). */
    fun scaled(src: BufferedImage, size: Int): BufferedImage {
        val dst = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return dst
    }

    /** [src] at every size in [sizes]. */
    fun icons(src: BufferedImage, sizes: List<Int> = SIZES): List<SniIcon> =
        sizes.map { s -> SniIcon(s, s, argb32(if (src.width == s && src.height == s) src else scaled(src, s))) }

    /** The colour tray icon resource at [SIZES]; empty when it can't be read (the host then shows a blank). */
    fun fromResource(name: String): List<SniIcon> = runCatching {
        val img = SniPixmap::class.java.classLoader.getResourceAsStream(name)?.use { ImageIO.read(it) }
            ?: return emptyList()
        icons(img)
    }.onFailure { System.err.println("supermux tray: can't load $name: ${it.message}") }.getOrDefault(emptyList())
}
