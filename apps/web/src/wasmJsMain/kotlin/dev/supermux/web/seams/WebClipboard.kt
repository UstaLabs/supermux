package dev.supermux.web.seams

import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.PickedFile

/**
 * Pasted images. The async Clipboard API can only be queried inside a user gesture and is
 * promise-based, so [hasImage] cannot answer synchronously; plan 2 reports none
 * (`Caps.clipboardImages=false` hides the affordance). Plan 3 wires `navigator.clipboard.read()`.
 */
object WebClipboard : ClipboardAccess {
    override suspend fun readImages(): List<PickedFile> = emptyList()
    override fun hasImage(): Boolean = false
}
