package dev.supermux.ui.chat

import androidx.compose.ui.window.DialogProperties

/**
 * The media viewer's dialog: full width, and on a phone full screen too, drawn under the status and
 * navigation bars so the black runs to every edge (the chat must not show through below the
 * buttons). The viewer keeps its own top bar and clip controls clear of those bars.
 */
internal expect fun viewerDialogProperties(): DialogProperties
