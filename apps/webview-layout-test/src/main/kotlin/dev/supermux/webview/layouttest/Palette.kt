package dev.supermux.webview.layouttest

import androidx.compose.ui.graphics.Color

/** The colours the window, the test page and the screenshot checks agree on: saturated, so they survive colour management. */
object Palette {
    /** The page's background (TestPage.kt). */
    const val PAGE_CSS = "#2040ff"

    /** The page's border band, [BAND_CSS_PX] CSS px wide on every edge of its viewport. */
    const val BAND_CSS = "#ff00ff"
    const val BAND_CSS_PX = 8

    /** The beacon in the top bar and the markers around the page slot. */
    val marker = Color(0xFF00E5FF)
    const val MARKER_DP = 6

    val menu = Color(0xFF00C853)
    val dialog = Color(0xFFFF9100)
    val scrim = Color.Black.copy(alpha = 0.32f)
    val placeholder = Color(0xFF9E9E9E)
    val rowA = Color(0xFFF2F2F2)
    val rowB = Color(0xFFE6E6E6)
    val handle = Color(0xFFBDBDBD)
}
