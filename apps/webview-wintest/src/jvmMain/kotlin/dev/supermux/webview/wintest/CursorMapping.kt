package dev.supermux.webview.wintest

import java.awt.Cursor

/**
 * WebView2's cursor (ICoreWebView2CompositionController::get_SystemCursorId, an IDC_* value) as an AWT cursor. AWT's
 * predefined cursors are these same system cursors on Windows; for the ids AWT has no type for, the surface builds a
 * custom cursor from the system cursor's own image.
 */
object CursorMapping {
    const val IDC_ARROW = 32512
    const val IDC_IBEAM = 32513
    const val IDC_WAIT = 32514
    const val IDC_CROSS = 32515
    const val IDC_SIZENWSE = 32642
    const val IDC_SIZENESW = 32643
    const val IDC_SIZEWE = 32644
    const val IDC_SIZENS = 32645
    const val IDC_SIZEALL = 32646
    const val IDC_NO = 32648
    const val IDC_HAND = 32649
    const val IDC_APPSTARTING = 32650
    const val IDC_HELP = 32651

    /** The java.awt.Cursor type that shows [idc] on Windows, or null where AWT has none. */
    fun awtType(idc: Int): Int? = when (idc) {
        IDC_ARROW -> Cursor.DEFAULT_CURSOR
        IDC_IBEAM -> Cursor.TEXT_CURSOR
        IDC_WAIT -> Cursor.WAIT_CURSOR
        IDC_CROSS -> Cursor.CROSSHAIR_CURSOR
        IDC_SIZENWSE -> Cursor.NW_RESIZE_CURSOR
        IDC_SIZENESW -> Cursor.NE_RESIZE_CURSOR
        IDC_SIZEWE -> Cursor.E_RESIZE_CURSOR
        IDC_SIZENS -> Cursor.N_RESIZE_CURSOR
        IDC_SIZEALL -> Cursor.MOVE_CURSOR
        IDC_HAND -> Cursor.HAND_CURSOR
        else -> null
    }

    /**
     * What GetCursorInfo should report over an element with CSS cursor [css] (native probes.cpp names it): an IDC_*
     * name, or "custom". For a custom cursor copied from a system cursor, [sameImageAs] is that cursor's id.
     */
    data class Expected(val name: String, val sameImageAs: Int? = null)

    fun expectedFor(css: String): Expected = when (css) {
        "pointer" -> Expected("IDC_HAND")
        "text" -> Expected("IDC_IBEAM")
        "crosshair" -> Expected("IDC_CROSS")
        "wait" -> Expected("IDC_WAIT")
        "move" -> Expected("IDC_SIZEALL")
        "ew-resize" -> Expected("IDC_SIZEWE")
        "ns-resize" -> Expected("IDC_SIZENS")
        "progress" -> Expected("custom", sameImageAs = IDC_APPSTARTING)
        "not-allowed" -> Expected("custom", sameImageAs = IDC_NO)
        else -> Expected("custom")
    }
}
