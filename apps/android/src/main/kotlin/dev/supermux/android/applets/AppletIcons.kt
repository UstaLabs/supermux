// #117: an applet's icon as Android draws it outside the app (a pinned shortcut, a widget): its own image, or the
// app's lettered tile — the project tiles' colour (hsl of the title's hash, 45 %, 42 %) and the first letter in white.
package dev.supermux.android.applets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader

internal object AppletIcons {
    /**
     * A [size] px square: [bytes] decoded and inset by [inset] of the size on white, or the lettered tile. [circle]
     * crops it round (widgets: Android draws applets as circles; older Home screens can't round an image themselves).
     */
    fun bitmap(bytes: ByteArray?, title: String, size: Int, inset: Float = 1f / 6, circle: Boolean = false, letter: Float = 0.36f): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val decoded = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (decoded != null) {
            c.drawColor(Color.WHITE)
            val px = (size * inset).toInt()
            c.drawBitmap(decoded, null, Rect(px, px, size - px, size - px), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val hue = ((title.hashCode() ushr 1) % 360).toFloat()
            c.drawColor(Color.HSVToColor(hslToHsv(hue, 0.45f, 0.42f)))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size * letter
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val y = size / 2f - (p.descent() + p.ascent()) / 2f
            c.drawText((title.firstOrNull() ?: '?').uppercaseChar().toString(), size / 2f, y, p)
        }
        if (!circle) return out
        val round = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(out, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(round).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        out.recycle()
        return round
    }

    private fun hslToHsv(h: Float, s: Float, l: Float): FloatArray {
        val v = l + s * minOf(l, 1 - l)
        val sv = if (v == 0f) 0f else 2 * (1 - l / v)
        return floatArrayOf(h, sv, v)
    }
}
