package dev.supermux.editor.compose

/**
 * True on macOS, iOS and iPadOS (and a browser running on one): `Mod` is Cmd there, and the
 * default keymap follows the platform's conventions (Alt-Arrow moves by word, Cmd-Arrow to the
 * line's ends).
 */
val isApplePlatform: Boolean by lazy { detectApplePlatform() }

internal expect fun detectApplePlatform(): Boolean


/**
 * The web's key path (see [webKeyDown]) and its clipboard events; elsewhere null. Installed while an editor is
 * composed; returns the function that removes it.
 */
internal expect fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)?

/**
 * The platform's text magnifier over the surface, centred on [center] (surface pixels; Unspecified
 * hides it): Android's `Modifier.magnifier` while a finger drags a selection end. Elsewhere nothing:
 * Compose has no magnifier on iOS and our own loupe is cut from M3b (documented in the README),
 * and desktop and web have no fingers to hide the text.
 */
internal expect fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier

/**
 * True where the platform's text toolbar is the touch selection menu (Android, iOS); false where
 * the surface draws its own (desktop, web).
 */
internal expect val platformTextToolbarPreferred: Boolean

/** [EditorController.inputOnAnyFocus]'s platform default. */
internal expect val platformInputOnAnyFocus: Boolean

/**
 * The web only: make the browser's own text input (Compose's TEXTAREA) hold [f]'s text and
 * selection, so IME and `insertText` land at the editor's caret, not where the DOM caret was left.
 * Elsewhere nothing (the platform input connection follows the field state).
 */
internal expect fun syncPlatformField(f: FieldText)

/**
 * Where the surface's own text node is what a screen reader reads (desktop, Android, iOS). On the
 * web the browser's focused TEXTAREA is always in the accessibility tree (Chrome refuses
 * aria-hidden on a focused element), so it IS the editor's one text box there: labelled with the
 * editor's label ([platformFieldLabel]), holding the lines around the caret, its selection kept on
 * the editor's caret; the surface then exposes no second one.
 */
internal expect val platformSurfaceText: Boolean

/** The web: the TEXTAREA's accessible name. Elsewhere nothing. */
internal expect fun platformFieldLabel(label: String)

/** iOS and the web: the hidden field's semantics are cleared, not merely hidden (see LocalEditorExposeField). */
internal expect val platformClearsFieldSemantics: Boolean

/**
 * Android: show the soft keyboard for the focused Compose view (`InputMethodManager.showSoftInput`).
 * Restarting the field's input session re-shows it only on the FIRST focus (Android shows an
 * editor's keyboard when a touch focuses it); after the user dismissed it, a later tap needs an
 * explicit request. iOS: the input view becomes first responder only through
 * `SoftwareKeyboardController.show()` while a session exists. Elsewhere null.
 */
@androidx.compose.runtime.Composable
internal expect fun rememberPlatformKeyboardShow(): (() -> Unit)?

/** A frame after the keyboard was asked for (iOS: check the Smart Punctuation traits). */
internal expect fun platformAfterKeyboardShown()
