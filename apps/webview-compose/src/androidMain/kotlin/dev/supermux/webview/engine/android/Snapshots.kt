package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import dev.supermux.webview.runtime.pixelsWithin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The longest a page's own draw ([drawPage]) should take on the main thread. One that takes longer is still kept, as
 * the main thread has already waited for it, but views as big are drawn smaller from then on ([PageDrawBudget]).
 */
internal const val MAX_PAGE_DRAW_MILLIS: Long = 32

/**
 * How many pixels this device's page draws ([drawPage]) fit in [MAX_PAGE_DRAW_MILLIS]. Null until a warm draw (not a
 * page's first, which is two to four times slower) takes longer: every page is drawn at the screen's own density (3
 * pixels a dp at most). From then on, the pixels that draw would have fit, with a margin ([pixelsWithin]), and a view
 * too big for them is drawn at the largest of 3, 2.5, 2 and 1.5 pixels a dp that fits ([drawScale]); smaller views keep
 * the screen's density. For the life of the process, for every engine: the same device draws pages about as fast.
 * Main thread only.
 */
internal object PageDrawBudget {
    var maxPixels: Long? = null

    /** A draw of [pixels] took [nanos], longer than the bound: fewer are drawn from now on. The new budget. */
    fun overran(pixels: Long, nanos: Long): Long {
        val within = pixelsWithin(pixels, nanos, MAX_PAGE_DRAW_MILLIS * 1_000_000)
        return minOf(within, maxPixels ?: within).also { maxPixels = it }
    }
}

/** What drawing a page by itself into a bitmap gave ([drawPage]). */
internal sealed interface PageDraw {
    /** How long the draw took, on the main thread. */
    val nanos: Long

    /** The page as it is on screen. */
    class Picture(val bitmap: Bitmap, override val nanos: Long) : PageDraw {
        /** Whether it took longer than [MAX_PAGE_DRAW_MILLIS]. */
        val overran: Boolean get() = nanos > MAX_PAGE_DRAW_MILLIS * 1_000_000
    }

    /** It came out all one colour: content the software path can't draw (video, WebGL), most likely. */
    class Blank(override val nanos: Long) : PageDraw

    /** The draw threw. */
    class Failed(val error: RuntimeException, override val nanos: Long) : PageDraw
}

/**
 * [view] drawn by itself into a bitmap of [size], scaled from its own size: the page as it is on screen, and nothing the
 * app draws over it (its header, a menu, a dialog, the runtime's cover), which a copy of the window would include.
 * WebView draws a software canvas in software, on the main thread: content that path can't draw (video, WebGL) may come
 * out blank, so a bitmap all of one colour is discarded ([isBlank]). In full colour, never 16-bit.
 */
internal fun drawPage(view: View, size: IntSize): PageDraw {
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(size.width.toFloat() / view.width, size.height.toFloat() / view.height)
    // A view draws its content at its scroll position; the part on screen starts there.
    canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
    val start = SystemClock.elapsedRealtimeNanos()
    try {
        view.draw(canvas)
    } catch (e: RuntimeException) {
        bitmap.recycle()
        return PageDraw.Failed(e, SystemClock.elapsedRealtimeNanos() - start)
    }
    val nanos = SystemClock.elapsedRealtimeNanos() - start
    return if (isBlank(bitmap)) PageDraw.Blank(nanos).also { bitmap.recycle() } else PageDraw.Picture(bitmap, nanos)
}

/**
 * Whether [bitmap] is all one colour, on a grid of 16 × 16 samples over it. A page that painted gives a picture with
 * something on it; one all of one colour is what a draw that couldn't reach the page's content gives, and is no better
 * than the placeholder anyway.
 */
internal fun isBlank(bitmap: Bitmap): Boolean {
    val first = bitmap.getPixel(0, 0)
    for (i in 0 until BLANK_GRID) {
        val x = (bitmap.width - 1) * i / (BLANK_GRID - 1)
        for (j in 0 until BLANK_GRID) {
            val y = (bitmap.height - 1) * j / (BLANK_GRID - 1)
            if (bitmap.getPixel(x, y) != first) return false
        }
    }
    return true
}

private const val BLANK_GRID = 16

/**
 * The pixels of [view] as its window shows them, scaled to [size], or null. PixelCopy reads the window's surface, so
 * the view must be attached and wholly inside the visible part of its window: a partly scrolled-away view would give a
 * partial page, which the cover would stretch. App content drawn over the view in the same window is in the copy, so
 * the engine takes one only when nothing the runtime knows of is over the page ([drawPage] comes first); popups and
 * dialogs are windows of their own and are not. A view in a Compose `Popup` (no Window object before API 34) gives null.
 */
internal suspend fun copyPixels(view: View, size: IntSize): Bitmap? {
    if (!wholeOnScreen(view)) return null
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val source = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val copied = if (Build.VERSION.SDK_INT >= 34) copyOnApi34(view, source, full) else copyFromWindow(view, source, full)
    if (!copied) {
        full.recycle()
        return null
    }
    if (size.width >= view.width && size.height >= view.height) return full
    val scaled = Bitmap.createScaledBitmap(full, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), true)
    if (scaled !== full) full.recycle()
    return scaled
}

/** Whether [view] is attached and wholly inside the visible part of its window. */
internal fun wholeOnScreen(view: View): Boolean {
    val visible = Rect()
    return view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height
}

@RequiresApi(34)
private suspend fun copyOnApi34(view: View, source: Rect, into: Bitmap): Boolean = suspendCancellableCoroutine { continuation ->
    val request = PixelCopy.Request.Builder.ofWindow(view).setSourceRect(source).setDestinationBitmap(into).build()
    PixelCopy.request(request, { it.run() }) { result ->
        if (continuation.isActive) continuation.resume(result.status == PixelCopy.SUCCESS)
    }
}

private suspend fun copyFromWindow(view: View, source: Rect, into: Bitmap): Boolean {
    val window = windowOf(view) ?: return false
    return suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            window,
            source,
            into,
            { status -> if (continuation.isActive) continuation.resume(status == PixelCopy.SUCCESS) },
            Handler(Looper.getMainLooper()),
        )
    }
}

/** The Window [view] is in: a Compose Dialog's, or its Activity's; null for anything else. */
private fun windowOf(view: View): Window? {
    var parent: View? = view
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent as? View
    }
    val activity = view.context.findActivity() ?: return null
    return activity.window.takeIf { it.peekDecorView() === view.rootView }
}
