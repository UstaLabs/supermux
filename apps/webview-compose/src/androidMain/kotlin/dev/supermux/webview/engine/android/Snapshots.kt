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
import dev.supermux.webview.runtime.DrawBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The longest a page's own draw ([drawPage]) should take on the main thread. One that takes longer is still kept, as
 * the main thread has already waited for it, but views as big are drawn smaller from then on ([PageDrawBudget]).
 */
internal const val MAX_PAGE_DRAW_MILLIS: Long = 32

/**
 * How many pixels this device's page draws ([drawPage]) fit in [MAX_PAGE_DRAW_MILLIS] ([DrawBudget]): every draw counts,
 * a document's first at a third of its time, and good draws raise a lowered budget again. Null until one takes too
 * long: every page is drawn at the screen's own density (3 pixels a dp at most). From then on, a view too big for it is
 * drawn at the largest of 3, 2.5, 2 and 1.5 pixels a dp that fits ([dev.supermux.webview.runtime.drawScale]); smaller
 * views keep the screen's density. For the life of the process, for every engine: the same device draws pages about as
 * fast. Main thread only.
 */
internal object PageDrawBudget : DrawBudget(MAX_PAGE_DRAW_MILLIS * 1_000_000) {
    /** A warm draw of [pixels] took [nanos], longer than the bound: fewer are drawn from now on. The new budget. */
    fun overran(pixels: Long, nanos: Long): Long = checkNotNull(drawn(pixels, nanos, cold = false))
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
 * out blank, so a bitmap all of one colour is discarded ([isBlank], checked off the main thread). In full colour, never
 * 16-bit.
 */
internal suspend fun drawPage(view: View, size: IntSize): PageDraw {
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
    val blank = withContext(Dispatchers.Default) { isBlank(bitmap) }
    return if (blank) PageDraw.Blank(nanos).also { bitmap.recycle() } else PageDraw.Picture(bitmap, nanos)
}

/**
 * Whether [bitmap] is all one colour, every pixel of it: what a draw that couldn't reach the page's content gives, no
 * better than the placeholder anyway. A page that painted has something on it, if only a line of text or a button on a
 * plain background; a grid of samples took such a sparse page for a blank one. It stops at the first pixel that
 * differs, so only a blank draw is read whole: off the main thread.
 */
internal fun isBlank(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return true
    val first = bitmap.getPixel(0, 0)
    val rows = maxOf(1, BLANK_CHUNK_PIXELS / width)
    val chunk = IntArray(width * rows)
    var y = 0
    while (y < height) {
        val count = minOf(rows, height - y)
        bitmap.getPixels(chunk, 0, width, 0, y, width, count)
        for (i in 0 until width * count) if (chunk[i] != first) return false
        y += count
    }
    return true
}

/** How many pixels [isBlank] reads at a time. */
private const val BLANK_CHUNK_PIXELS = 64 * 1024

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
    val (width, height) = view.width to view.height
    // The copy runs on the render thread; the main thread only asks for it. Its bitmap is made, and scaled, off it.
    val full = withContext(Dispatchers.Default) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    val copied = if (Build.VERSION.SDK_INT >= 34) copyOnApi34(view, source, full) else copyFromWindow(view, source, full)
    if (!copied) {
        full.recycle()
        return null
    }
    if (size.width >= width && size.height >= height) return full
    return withContext(Dispatchers.Default) {
        val scaled = Bitmap.createScaledBitmap(full, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), true)
        if (scaled !== full) full.recycle()
        scaled
    }
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
