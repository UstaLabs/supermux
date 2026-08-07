package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.toDomainOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A splitter drag fires on every pointer move; one PATCH per move would flood every peer. */
internal const val LAYOUT_PATCH_DEBOUNCE_MS = 300L

/**
 * The open workspace's layout tree as the UI edits it, plus the debounced
 * write-back to the broker.
 *
 * The tree round-trips: a local edit is PATCHed, the broker persists it and
 * broadcasts `workspace_changed`, and that frame is what every client (this one
 * included) finally renders. Rendering straight from the broker would make a
 * splitter drag stutter at network latency, so the UI renders [tree] — the local
 * copy — and [dirty] marks "the broker has not confirmed this yet".
 *
 * [dirty] is the whole contract, and it is a single flag for the WHOLE tree, not
 * per group. While it is set, incoming `workspace_changed` frames are ignored;
 * the moment it clears, the next frame is adopted wholesale. So clearing it for
 * a tree the broker never actually took is not a small error: the broker's older
 * tree then overwrites every group, including the panes the user never touched.
 */
internal class WorkspaceLayoutState(initial: LayoutNode) {
    /** What LayoutHost renders and what the next PATCH will send. */
    var tree by mutableStateOf(initial)
        private set

    /** True from a local edit until the broker has that exact tree. */
    var dirty by mutableStateOf(false)
        private set

    /** A local edit (tab click, drag, splitter, add-view). */
    fun edit(next: LayoutNode) {
        tree = next
        dirty = true
    }

    /** Adopt the broker's tree. Only called while [dirty] is false. */
    internal fun adopt(next: LayoutNode) {
        tree = next
    }

    internal fun markClean() {
        dirty = false
    }
}

/**
 * Wire one workspace's layout to the broker.
 *
 * [serverLayout] is the tree off the latest `workspace_changed` frame; [push]
 * performs the PATCH. Both effects below are the round trip, in the two
 * directions:
 *
 *  - broker → UI: adopt a frame, but only when the user has no unconfirmed edit.
 *  - UI → broker: debounce, PATCH, and clear the flag ONLY on a confirmed write.
 */
@Composable
internal fun rememberWorkspaceLayout(
    workspaceId: String,
    serverLayout: LayoutNodeDto?,
    push: suspend (LayoutNode) -> Unit,
): WorkspaceLayoutState {
    val state = remember(workspaceId) { WorkspaceLayoutState(serverLayout.toDomain()) }

    LaunchedEffect(serverLayout) {
        if (!state.dirty) state.adopt(serverLayout.toDomain())
    }

    LaunchedEffect(state.tree, state.dirty) {
        if (!state.dirty) return@LaunchedEffect
        delay(LAYOUT_PATCH_DEBOUNCE_MS)
        try {
            push(state.tree)
        } catch (cancelled: CancellationException) {
            // A newer local edit changed the key and restarted this effect, which
            // threw the cancellation into the request that was still in flight.
            //
            // That is NOT a successful write, and it must not clear the flag. It
            // used to: `runCatching` catches Throwable, cancellation included, so
            // the tree we were still sending counted as confirmed. The newer tree
            // was then never PATCHed (the restarted job sees a clean flag and
            // returns), the broker kept the older one, and the next
            // workspace_changed — a view added anywhere, an agent renaming its
            // session, another device — was adopted WHOLE and rolled the layout
            // back across every group. That is what "click a tab on a split and it
            // also switches on another split" looked like.
            //
            // Rethrowing leaves [dirty] set, so the restarted job (which owns the
            // newer tree) is the one that confirms it.
            throw cancelled
        } catch (failed: Throwable) {
            // A rejected write is different: the broker refused this tree, so it is
            // the broker's tree we should be showing. Fall through and let the next
            // frame be adopted rather than pinning the client to a layout the
            // broker will never accept.
        }
        // Belt and braces for a transport that wraps cancellation in its own
        // exception type: never confirm a write from a job that is already dead.
        currentCoroutineContext().ensureActive()
        state.markClean()
    }

    return state
}

/** An absent/unreadable layout is an empty group, not a crash — the next addView needs a target. */
private fun LayoutNodeDto?.toDomain(): LayoutNode =
    this.toDomainOrNull() ?: LayoutNode.Group(id = "g", viewIds = emptyList())
