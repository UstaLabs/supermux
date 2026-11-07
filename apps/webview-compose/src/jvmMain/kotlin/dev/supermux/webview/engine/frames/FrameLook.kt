package dev.supermux.webview.engine.frames

/**
 * What a frame shows, from nine points of it ([FrameStore] with `looks`): nothing, every point clear; one colour; or
 * more. WebView2's blank frames are its empty visual (clear) and its default background (one colour); Windows' first
 * paint never takes one for a page that is neither ([dev.supermux.webview.engine.windows.WindowsWebEngine]).
 */
internal sealed interface FrameLook {
    data object Clear : FrameLook

    /** Every point [argb] (0xAARRGGBB, premultiplied as the frame is), within two levels a channel. */
    data class Uniform(val argb: Int) : FrameLook

    data object Varied : FrameLook
}

/** [top] (0xAARRGGBB, not premultiplied: a CSS colour) over the opaque [bottom]: the colour a frame shows. */
internal fun over(top: Int, bottom: Int): Int {
    val alpha = (top ushr 24) / 255.0
    val channel = { shift: Int -> ((top ushr shift and 0xFF) * alpha + (bottom ushr shift and 0xFF) * (1 - alpha)).toInt() and 0xFF }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Whether two 0xAARRGGBB colours are within two levels in every channel. */
internal fun sameColour(a: Int, b: Int): Boolean =
    (0..24 step 8).all { shift -> kotlin.math.abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)) <= 2 }
