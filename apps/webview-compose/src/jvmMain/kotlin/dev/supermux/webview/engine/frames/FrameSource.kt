package dev.supermux.webview.engine.frames

/**
 * Where a frame engine's frames come from, as its `FrameStore` pulls them: WPE's view (`native/linux`) or WebView2's
 * capture (`native/windows`). Main thread.
 */
internal interface FrameSource {
    /** The newest frame the engine has delivered, by its sequence; -1 if none. */
    fun frameSeq(): Long

    /**
     * Copies what changed in the newest frame into [dst] (`rowBytes` a row, [width] × [height] pixels, BGRA premultiplied),
     * or all of it if [full]. Returns 1 when it copied, 0 when nothing is new, -1 when the frame is another size (then
     * `out[1]`, `out[2]` are its width and height and nothing is copied). `out[0]` is the copied frame's sequence; the
     * rest of [out] is the source's own (damage, timings).
     */
    fun pullFrame(dst: Long, rowBytes: Int, width: Int, height: Int, full: Boolean, out: LongArray): Int
}
