// The Settings page frame and the anchors search jumps to (#97).
package dev.supermux.ui.settings.kit

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/**
 * The rows of one page that search can land on. [target] is the anchor the page was opened for (folds holding it
 * open themselves); [flashing] is the one currently tinted.
 */
@Stable
class KitAnchors(target: String? = null) {
    var target by mutableStateOf(target)
    var flashing by mutableStateOf<String?>(null)
    internal val requesters = mutableMapOf<String, BringIntoViewRequester>()
}

/** Null outside a [KitPage]: a row's anchor is then only a semantics tag. */
val LocalKitAnchors = staticCompositionLocalOf<KitAnchors?> { null }

/** The anchor a row carries, for tests and for the search index check. */
val KitAnchorKey = SemanticsPropertyKey<String>("KitAnchor")
var SemanticsPropertyReceiver.kitAnchor by KitAnchorKey
