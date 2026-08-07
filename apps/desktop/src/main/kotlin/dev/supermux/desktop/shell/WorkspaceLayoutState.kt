package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.normalizeLayout
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
 * A local edit is an OFFSET from the broker's tree, never a replacement for it.
 * That distinction is the whole design. This used to drop every frame that
 * arrived while an edit was unconfirmed (`if (!dirty) adopt(server)`), so a
 * delete the broker made during the ~300ms debounce was simply never seen: by
 * the time the flag cleared, that frame was gone and no other was coming. The
 * client then PATCHed its pre-delete tree back over the broker's, resurrecting a
 * closed view — which draws as a tab titled "view", the LayoutHost fallback for
 * a view id with no record behind it.
 *
 * So the unconfirmed edit is kept as a FUNCTION, and a frame that lands mid-edit
 * is rebased: `tree = pending(serverTree)`. The broker's membership always wins;
 * the user's arrangement is replayed on top of it. Every layout primitive names
 * what it touches by group id or split path, so replaying onto a tree where the
 * target is gone is a clean no-op rather than a rollback — a resize can only ever
 * say "set the sizes at this path", which cannot revive anything.
 *
 * Replaying also has to be idempotent, because the broker echoes our own PATCH
 * back. Every primitive used here is: setSplitSizes and setActiveViewInGroup
 * write a value, addViewToGroup and moveViewToGroup no-op once the view is in
 * place, and splitGroup requires the view to still be in the source group.
 */
internal class WorkspaceLayoutState(initial: LayoutNode) {
    /** What LayoutHost renders and what the next PATCH will send. */
    var tree by mutableStateOf(initial)
        private set

    /** True from a local edit until the broker has that exact tree. */
    var dirty by mutableStateOf(false)
        private set

    /**
     * Every edit since the last confirmed write, composed into one function.
     * Null when the broker is known to hold what we hold.
     */
    private var pending: ((LayoutNode) -> LayoutNode)? = null

    /**
     * A local edit (tab click, drag, splitter, add-view), expressed as a
     * transform of the tree rather than a replacement for it.
     */
    fun edit(transform: (LayoutNode) -> LayoutNode) {
        val prior = pending
        pending = if (prior == null) transform else ({ node -> transform(prior(node)) })
        tree = normalizeLayout(transform(tree)) ?: tree
        dirty = true
    }

    /**
     * A `workspace_changed` frame. With nothing pending this is a plain adopt;
     * otherwise the unconfirmed edit is replayed on top of it.
     */
    internal fun onServerFrame(server: LayoutNode) {
        val replay = pending
        tree = if (replay == null) server else (normalizeLayout(replay(server)) ?: server)
    }

    /** The broker now holds [tree]: nothing is outstanding. */
    internal fun markClean() {
        pending = null
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
 *  - broker → UI: take EVERY frame, replaying any unconfirmed edit on top of it.
 *  - UI → broker: debounce, PATCH, and clear the flag ONLY on a confirmed write.
 */
@Composable
internal fun rememberWorkspaceLayout(
    workspaceId: String,
    serverLayout: LayoutNodeDto?,
    push: suspend (LayoutNode) -> Unit,
): WorkspaceLayoutState {
    val state = remember(workspaceId) { WorkspaceLayoutState(serverLayout.toDomain()) }

    // No `if (!dirty)` guard: a frame is the broker's truth and is never
    // discarded. When an edit is outstanding it is replayed over the frame.
    LaunchedEffect(serverLayout) {
        state.onServerFrame(serverLayout.toDomain())
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
