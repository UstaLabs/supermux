package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The pixels of [view] as its window shows them, scaled by [scale], or null. PixelCopy reads the window's surface, so
 * the view must be attached and wholly inside the visible part of its window: a partly scrolled-away view would give a
 * partial page, which the cover would stretch. App content drawn over the view in the same window is in the copy;
 * popups and dialogs are windows of their own and are not. A view in a Compose `Popup` (no Window object before API 34)
 * gives null.
 */
internal suspend fun copyPixels(view: View, scale: Float): Bitmap? {
    val visible = Rect()
    if (!view.getGlobalVisibleRect(visible) || visible.width() != view.width || visible.height() != view.height) return null
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val source = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val copied = if (Build.VERSION.SDK_INT >= 34) copyOnApi34(view, source, full) else copyFromWindow(view, source, full)
    if (!copied) {
        full.recycle()
        return null
    }
    if (scale >= 1f) return full
    val scaled = Bitmap.createScaledBitmap(
        full,
        (view.width * scale).roundToInt().coerceAtLeast(1),
        (view.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== full) full.recycle()
    return scaled
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
