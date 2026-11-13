// Whether other windows cover the main window entirely (card #132). Only the purely decorative loops read it,
// through LocalAvatarsLive: avatars, spinners and pulses stand still behind another window. ScreenWatch's "seen",
// which mutes a chat's push, never does. macOS asks AppKit; Windows and Linux cannot tell cheaply and count as seen.
package dev.supermux.desktop.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.supermux.desktop.overlay.MacOverlayNative
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** NSWindowOcclusionStateVisible: some of the window can be seen. */
private const val OCCLUSION_VISIBLE = 1L shl 1

/** Whether an `occlusionState` says no part of the window can be seen. */
internal fun occlusionCovers(occlusionState: Long): Boolean = occlusionState and OCCLUSION_VISIBLE == 0L

/** Asks whether the NSWindow [handle] is entirely covered; null when it cannot tell. */
internal fun interface CoverProbe {
    suspend fun covered(handle: Long): Boolean?
}

/** macOS: the window's `occlusionState`, read on AppKit's main thread. */
internal object MacCoverProbe : CoverProbe {
    private val objc by lazy { NativeLibrary.getInstance("objc") }

    override suspend fun covered(handle: Long): Boolean? {
        if (handle == 0L) return null
        val answer = CompletableDeferred<Long?>()
        MacOverlayNative.onMain {
            answer.complete(
                runCatching {
                    val sel = objc.getFunction("sel_registerName").invokePointer(arrayOf("occlusionState"))
                    objc.getFunction("objc_msgSend").invokeLong(arrayOf(Pointer(handle), sel))
                }.getOrNull(),
            )
        }
        // A busy main thread answers late: this check is for looks only, so it never waits long.
        return withTimeoutOrNull(500) { answer.await() }?.let(::occlusionCovers)
    }
}

/**
 * True while other windows cover the window (its NSWindow [handle]) entirely. Asked every [periodMs] while [watch]
 * (it is showing, not minimised, the screen seen). Focus says nothing: a panel that never takes focus can cover
 * the focused window.
 */
@Composable
internal fun rememberWindowCovered(
    handle: () -> Long,
    watch: Boolean,
    probe: CoverProbe? = if (isMacOs()) MacCoverProbe else null,
    periodMs: Long = 1_000,
): State<Boolean> = produceState(false, watch, probe) {
    value = false
    if (probe == null || !watch) return@produceState
    while (true) {
        value = probe.covered(handle()) ?: false
        delay(periodMs)
    }
}
