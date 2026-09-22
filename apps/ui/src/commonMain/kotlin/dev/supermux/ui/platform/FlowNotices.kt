package dev.supermux.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A [NoticeChannel] that is an in-memory bus, for every host with no OS-level transient message.
 *
 * Android has a Toast and uses it. Desktop and iOS do not — a desktop window has no such thing, and
 * iOS has never had one (an app that wants a transient message draws it) — so both raise the
 * shared shell's one-line "couldn't open that" as a Compose snackbar instead. That is the same
 * mechanism twice, so it lives here rather than once per host.
 *
 * `extraBufferCapacity = 8` with `DROP_OLDEST` and `tryEmit`: [show] is called from ordinary UI
 * callbacks and from non-suspending seams, so it must never block or fail. Losing the oldest of
 * nine simultaneous notices is the right trade — they are transient by definition, and the newest
 * is the one the user is waiting on.
 */
class FlowNotices : NoticeChannel {
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Drained by [NoticeOverlay] at the host's theme root. */
    val messages: SharedFlow<String> = _messages

    override fun show(text: String) {
        if (text.isNotBlank()) _messages.tryEmit(text)
    }
}

/**
 * Draws [content] with a snackbar host over its bottom edge, fed by [notices].
 *
 * A `Box` and not a `Scaffold`, deliberately: a Scaffold here would give every screen in the app an
 * inset it did not ask for and re-lay-out the whole tree the first time a notice appeared. This
 * draws OVER the content and shifts nothing.
 *
 * Belongs at the theme root, above the app root, so one host serves every screen and a notice
 * raised while navigating still lands.
 */
@Composable
fun NoticeOverlay(notices: FlowNotices, content: @Composable () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(notices) {
        notices.messages.collect { snackbars.showSnackbar(it) }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        SnackbarHost(
            hostState = snackbars,
            // `safeDrawingPadding` before the visual padding: the iOS host draws edge-to-edge
            // (`.ignoresSafeArea()`, because Compose owns the insets), so without this the
            // snackbar sits under the home indicator — legible, but with its action button in the
            // one strip of screen the system takes for itself. Desktop insets are zero, so this is
            // free there.
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
        )
    }
}
