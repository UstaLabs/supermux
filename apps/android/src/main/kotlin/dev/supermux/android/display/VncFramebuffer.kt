package dev.supermux.android.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import dev.supermux.net.VncRect

/**
 * Software VNC framebuffer renderer. One ARGB_8888 [Bitmap] holds the whole
 * framebuffer (the analog of the iOS Metal texture + CPU mirror in VncMetalView).
 * Each VncRect update blits into it (BGRA→ARGB swizzle for Raw; an intra-bitmap
 * Canvas blit for CopyRect), then the whole bitmap is drawn aspect-fit (letterbox)
 * to the supplied Surface. All entry points are @Synchronized — applyUpdate() runs
 * on the WS collect coroutine; setSurface()/onSizeChanged() run on the main thread.
 */
class VncFramebuffer {
    private var bitmap: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private var surface: Surface? = null
    private var viewW = 0
    private var viewH = 0
    private val paint = Paint().apply { isFilterBitmap = true } // bilinear, like the Metal .linear sampler
    private var scratch = IntArray(0)                            // reused scratch (re-alloc on resize)

    @Synchronized fun setSurface(s: Surface?, w: Int, h: Int) {
        surface = s; viewW = w; viewH = h
        if (s != null) draw()
    }

    @Synchronized fun onSizeChanged(w: Int, h: Int) { viewW = w; viewH = h; draw() }

    /** Allocate/realloc the backing bitmap when the framebuffer size changes (ServerInit / DesktopSize). */
    private fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == fbW && h == fbH)) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fbW = w; fbH = h
        scratch = IntArray(w * h)
    }

    /**
     * Apply one FramebufferUpdate. [size] is the latest VncClient.size (w,h); we resize
     * the bitmap to match BEFORE blitting so a DesktopSize change reallocates first
     * (iOS does the same via VncHost calling coord.resize before applyUpdate).
     */
    @Synchronized fun applyUpdate(rects: List<VncRect>, size: Pair<Int, Int>?) {
        size?.let { resize(it.first, it.second) }
        val bmp = bitmap ?: return
        for (r in rects) {
            val x = r.x; val y = r.y; val w = r.width; val h = r.height
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > fbW || y + h > fbH) continue
            if (r.isCopy) {
                copyRect(bmp, r.srcX, r.srcY, x, y, w, h)
            } else {
                if (r.bgra.size < w * h * 4) continue
                uploadRaw(bmp, r.bgra, x, y, w, h)
            }
        }
        draw()
    }

    /** BGRA bytes → ARGB ints (swizzle B↔R, force opaque) → Bitmap.setPixels into the dest rect. */
    private fun uploadRaw(bmp: Bitmap, bgra: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val n = w * h
        val px = if (scratch.size >= n) scratch else IntArray(n)
        var s = 0; var d = 0
        while (d < n) {
            px[d] = (0xFF shl 24) or
                ((bgra[s + 2].toInt() and 0xFF) shl 16) or   // R
                ((bgra[s + 1].toInt() and 0xFF) shl 8) or    // G
                (bgra[s].toInt() and 0xFF)                   // B
            s += 4; d++
        }
        bmp.setPixels(px, 0, w, x, y, w, h)
    }

    /** CopyRect: copy a source sub-rect to a dest sub-rect within the SAME bitmap.
     *  Read the source into scratch first (handles overlap), then write the dest. */
    private fun copyRect(bmp: Bitmap, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        if (sx < 0 || sy < 0 || sx + w > fbW || sy + h > fbH) return
        val n = w * h
        val px = if (scratch.size >= n) scratch else IntArray(n)
        bmp.getPixels(px, 0, w, sx, sy, w, h)
        bmp.setPixels(px, 0, w, dx, dy, w, h)
    }

    /** Aspect-fit the bitmap into the surface (letterbox), black background. Mirrors the
     *  Metal aspect-fit in VncMetalView.draw() and the touch-map math in VncInput. */
    @Synchronized private fun draw() {
        val s = surface ?: return
        val bmp = bitmap ?: return
        if (viewW <= 0 || viewH <= 0 || fbW <= 0 || fbH <= 0) return
        val canvas: Canvas = try { s.lockCanvas(null) } catch (_: Throwable) { return }
        try {
            canvas.drawColor(Color.BLACK)
            val scale = minOf(viewW.toFloat() / fbW, viewH.toFloat() / fbH)
            val dispW = fbW * scale; val dispH = fbH * scale
            val offX = (viewW - dispW) / 2f; val offY = (viewH - dispH) / 2f
            canvas.drawBitmap(
                bmp, Rect(0, 0, fbW, fbH),
                RectF(offX, offY, offX + dispW, offY + dispH), paint,
            )
        } finally {
            try { s.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
        }
    }

    @Synchronized fun release() { bitmap?.recycle(); bitmap = null; fbW = 0; fbH = 0; surface = null }
}
