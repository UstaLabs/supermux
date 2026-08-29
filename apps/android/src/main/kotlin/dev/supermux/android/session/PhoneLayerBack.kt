package dev.supermux.android.session

/** What the phone session/workspace layer should do with a BACK event. */
enum class PhoneLayerBackAction {
    /** First BACK while the IME is up: hide the keyboard, stay in the layer. */
    HideIme,
    /** Next BACK (or BACK with IME already down): return to the session list. */
    ClearSelection,
    /** Wide/tablet, or the editor pane's own BackHandler owns the event. */
    None,
}

/**
 * Pure BACK routing for the phone keep-alive layers.
 *
 * Wide/tablet never consumes BACK (the list stays on-screen). The editor pane keeps its own
 * consume rule via [editorConsumesBack]. Otherwise: IME first, then clear the selection.
 */
fun phoneLayerBackAction(
    wide: Boolean,
    editorConsumesBack: Boolean,
    imeVisible: Boolean,
): PhoneLayerBackAction {
    if (wide || editorConsumesBack) return PhoneLayerBackAction.None
    if (imeVisible) return PhoneLayerBackAction.HideIme
    return PhoneLayerBackAction.ClearSelection
}
