// Ported from apps/android/.../display/VncInput.kt — pure geometry + X11 keysym tables, no UI, no
// session state. scrcpyKeyName is DROPPED here: h264/scrcpy device-mirroring is out of scope for
// the desktop client (no JVM MediaCodec equivalent, marginal use case) — see this milestone's Goal.
package dev.supermux.desktop.display

import dev.supermux.net.Keysyms

/**
 * Pure input-mapping helpers for the desktop VNC display transport. Letterbox math is
 * byte-identical to Android's [VncInput] and to Compose's own `ContentScale.Fit` formula, so a
 * click maps to exactly the remote pixel the aspect-fit `Image` composable is showing there.
 */
object VncInput {
    /** A special (non-character) key, forwarded by the display panel's key handler. */
    enum class SpecialKey { ENTER, BACKSPACE, TAB, ESCAPE, ARROW_LEFT, ARROW_UP, ARROW_RIGHT, ARROW_DOWN }

    /** Map a view-space point to remote framebuffer pixels (aspect-fit + center), clamped. */
    fun mapToRemote(px: Float, py: Float, viewW: Int, viewH: Int, remoteW: Int, remoteH: Int): Pair<Int, Int> {
        if (remoteW <= 0 || remoteH <= 0 || viewW <= 0 || viewH <= 0) return 0 to 0
        val scale = minOf(viewW.toFloat() / remoteW, viewH.toFloat() / remoteH)
        if (scale <= 0f) return 0 to 0
        val offX = (viewW - remoteW * scale) / 2f
        val offY = (viewH - remoteH * scale) / 2f
        val rx = ((px - offX) / scale).toInt().coerceIn(0, remoteW)
        val ry = ((py - offY) / scale).toInt().coerceIn(0, remoteH)
        return rx to ry
    }

    /** X11 keysym for a typed character (printable ASCII/Latin-1 → codepoint), or null. */
    fun keysymForChar(ch: Char): Long? = when {
        ch == '\n' || ch == '\r' -> Keysyms.RETURN
        ch == '\t' -> Keysyms.TAB
        ch.code == 0x7F || ch.code == 0x08 -> Keysyms.BACKSPACE
        ch.code in 0x20..0xFF -> ch.code.toLong()
        else -> null
    }

    /** X11 keysym for a [SpecialKey] (RFC 6143 §7.5.4), for VncClient.sendKey. */
    fun keysymForSpecial(key: SpecialKey): Long = when (key) {
        SpecialKey.ENTER -> Keysyms.RETURN
        SpecialKey.BACKSPACE -> Keysyms.BACKSPACE
        SpecialKey.TAB -> Keysyms.TAB
        SpecialKey.ESCAPE -> Keysyms.ESCAPE
        SpecialKey.ARROW_LEFT -> Keysyms.LEFT
        SpecialKey.ARROW_UP -> Keysyms.UP
        SpecialKey.ARROW_RIGHT -> Keysyms.RIGHT
        SpecialKey.ARROW_DOWN -> Keysyms.DOWN
    }
}
