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
import dev.supermux.workspace.validateLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import dev.supermux.ui.panes.PaneHost

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
 * closed view — which draws as a tab titled "view", the PaneHost fallback for
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
 *
 * Idempotent ON THE TREE THE EDIT WAS MADE AGAINST, though — not on any tree.
 * `addViewToGroup(tree, g, v)` only looks at `g`: replay it onto a frame where
 * the broker has put `v` somewhere ELSE and it adds a second copy, and one view
 * id in two groups draws as the same file in two tabs. So the replay is CHECKED,
 * not trusted: an edit that no longer produces a layout the broker would accept
 * is dropped, and the broker's frame stands on its own. `normalizeLayout` will
 * not catch this — it drops empty groups and collapses splits, and a duplicate
 * id survives it untouched.
 *
 * The other half of the same rule is [rollback]: an edit the broker REFUSES has
 * to be dropped too, or it is replayed over every frame for the rest of the
 * session. See the call site for why that branch used to be unreachable.
 */
internal class WorkspaceLayoutState(initial: LayoutNode) {
    /** What PaneHost renders and what the next PATCH will send. */
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

    /** The last tree the broker sent us — what [rollback] falls back to. */
    private var server: LayoutNode = initial

    /**
     * A local edit (tab click, drag, splitter, add-view), expressed as a
     * transform of the tree rather than a replacement for it.
     *
     * An edit that would corrupt the layout is refused outright rather than
     * drawn and then rejected by the broker: the tree on screen is always one
     * the broker could store.
     */
    fun edit(transform: (LayoutNode) -> LayoutNode) {
        val next = normalizeLayout(transform(tree)) ?: tree
        validateLayout(next)?.let { err ->
            println("[WorkspaceLayoutState] refusing a local edit that would corrupt the layout: $err")
            return
        }
        val prior = pending
        pending = if (prior == null) transform else ({ node -> transform(prior(node)) })
        tree = next
        dirty = true
    }

    /**
     * A `workspace_changed` frame. With nothing pending this is a plain adopt;
     * otherwise the unconfirmed edit is replayed on top of it — as long as the
     * result is still a layout the broker would take.
     */
    internal fun onServerFrame(frame: LayoutNode) {
        server = frame
        val replay = pending
        if (replay == null) {
            tree = frame
            return
        }
        val replayed = normalizeLayout(replay(frame))
        val err = if (replayed == null) "the edit leaves nothing to draw" else validateLayout(replayed)
        if (replayed == null || err != null) {
            // The broker's tree has moved somewhere our edit no longer describes. Drawing
            // the result anyway is how one view ends up in two groups; the broker would
            // refuse it, and we would keep replaying it. Let the frame stand.
            println("[WorkspaceLayoutState] dropping an unconfirmed edit that no longer applies: $err")
            pending = null
            dirty = false
            tree = frame
            return
        }
        tree = replayed
    }

    /**
     * The broker REFUSED this tree. Its own is the truth, so show that and stop
     * resending ours — an edit that cannot be written must never become a
     * permanent offset applied to every frame that follows.
     */
    internal fun rollback() {
        pending = null
        dirty = false
        tree = server
    }

    /** The broker now holds [tree]: nothing is outstanding. */
    internal fun markClean() {
        pending = null
        dirty = false
        server = tree
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
        } catch (failed: Throwable) {
            // Two very different things land here, and the exception TYPE cannot tell
            // them apart:
            //
            //  1. A newer local edit changed the key and restarted this effect, which
            //     threw the cancellation into the request still in flight. That is NOT
            //     a successful write and must not clear the flag — it used to, because
            //     `runCatching` catches cancellation too, so the tree we were still
            //     sending counted as confirmed. The broker kept the older one and the
            //     next workspace_changed was adopted WHOLE, rolling the layout back
            //     across every group: "click a tab on a split and it also switches on
            //     another split".
            //
            //  2. The broker REFUSED this tree (a 400). Then its tree is the one to
            //     show, and our edit has to go — an edit that cannot be written would
            //     otherwise be replayed onto every frame for the rest of the session.
            //
            // Distinguishing them by catching CancellationException for (1) made (2)
            // unreachable: `BrokerApi.decode` reports EVERY non-2xx as a
            // CancellationException on purpose (a raw throw out of a SKIE-bridged
            // suspend call SIGABRTs the iOS process), so a 400 was read as "superseded"
            // and rethrown, keeping the edit. The refused edit then went onto every
            // frame — re-adding a view the broker does not hold, and adding one that
            // had since moved a SECOND time, which draws the same file in two tabs.
            // Reported as "opening a file opens it twice".
            //
            // So ask the JOB, not the exception. Only a cancelled job was superseded;
            // ensureActive rethrows for (1) and falls through for (2).
            currentCoroutineContext().ensureActive()
            println("[workspace] layout write refused, dropping the unconfirmed edit: $failed")
            state.rollback()
            return@LaunchedEffect
        }
        // Never confirm a write from a job that is already dead.
        currentCoroutineContext().ensureActive()
        state.markClean()
    }

    return state
}

/** An absent/unreadable layout is an empty group, not a crash — the next addView needs a target. */
private fun LayoutNodeDto?.toDomain(): LayoutNode =
    this.toDomainOrNull() ?: LayoutNode.Group(id = "g", viewIds = emptyList())
