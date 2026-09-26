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
