// Opening a file, end to end against a stand-in broker that behaves like the real one.
//
// WorkspaceFileOpenTest drives the opener with the layout edit applied straight to a variable and a
// POST that answers instantly. That is the right shape for the placement RULES, and it cannot see
// this class of bug at all: every "the file opened twice" report so far has come from the ORDER the
// two requests an open makes land in — POST /views (immediate) and PATCH /workspaces/:id (debounced
// 300ms) — crossed with the workspace_changed frames each of them broadcasts back.
//
// So the broker here is a port of what src/core/workspace/store.ts actually does, latency included:
//   - addView INSERTS the row, then places the id with `placeView` — the caller's group is a
//     PREFERENCE, and the fallback appends to the first group. Then it broadcasts the layout.
//   - setLayout (the PATCH) REFUSES a tree naming a view the workspace does not have yet, which is
//     exactly what an optimistic open's own layout is until its POST has landed. Leaving that out
//     is what made the first version of this file blame the wrong end of the race.
// Both broadcasts go through the same `serverLayout` the real AppShell feeds rememberWorkspaceLayout.
package dev.supermux.desktop.shell

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import dev.supermux.workspace.validateLayout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

@OptIn(ExperimentalTestApi::class)
class FileOpenRoundTripTest {

    /** Port of `placeView` in src/core/workspace/store.ts. */
    private fun placeView(layout: LayoutNode, viewId: String, groupId: String?): LayoutNode {
        if (viewId in collectViewIds(layout)) return layout
        if (groupId != null) {
            val wanted = addViewToGroup(layout, groupId, viewId)
            if (viewId in collectViewIds(wanted)) return wanted
        }
        val first = firstGroupId(layout) ?: return layout
        return addViewToGroup(layout, first, viewId)
    }

    private fun editorView(id: String, mode: String, path: String? = null) = ViewDto(
        id = id, workspaceId = "w1", kind = "editor",
        state = JsonObject(
            buildMap {
                put("mode", JsonPrimitive(mode))
                if (path != null) put("path", JsonPrimitive(path))
            },
        ),
    )

    private var openerSlot: WorkspaceFileOpener? = null

    /**
     * One open of one path, with the POST answering after [postLatencyMs] and the layout PATCH after
     * [patchLatencyMs]. Returns every tree the UI drew, in order.
     */
    private fun runOpen(
        start: LayoutNode,
        startViews: Map<String, ViewDto>,
        postLatencyMs: Long,
        patchLatencyMs: Long = 40L,
        /** False models a broker older than 3bfb400c: it mints its own id and ignores ours. */
        honoursClientId: Boolean = true,
    ): List<LayoutNode> {
        // Written from the composition's applier thread and read from the test's: a plain
        // ArrayList loses frames to the memory model, which reads as a passing test.
        val drawn = java.util.concurrent.CopyOnWriteArrayList<LayoutNode>()
        runComposeUiTest {
            // ── the broker ────────────────────────────────────────────────────────────────────
            var serverLayout by mutableStateOf(start.toDto())
            val serverViews = mutableStateMapOf<String, ViewDto>().also { it.putAll(startViews) }

            setContent {
                val provisional = remember { mutableStateMapOf<String, ViewDto>() }
                // AppShell's stand-in cleanup: the broker's row replaces ours as soon as it lands.
                provisional.keys.filter { it in serverViews }.forEach { provisional.remove(it) }

                val sync = rememberWorkspaceLayout(
                    workspaceId = "w1",
                    serverLayout = serverLayout,
                    unconfirmedViews = provisional.keys.toSet(),
                    push = { next ->
                        delay(patchLatencyMs)
                        // WorkspaceStore.setLayout, faithfully: an invalid tree, or one naming a
                        // view this workspace does not have, is a 400 — and the client's push
                        // treats a throw that did not cancel the job as a refusal.
                        validateLayout(next)?.let { error(it) }
                        collectViewIds(next).firstOrNull { it !in serverViews }?.let {
                            error("layout names a view that is not in this workspace: $it")
                        }
                        withContext(NonCancellable) { serverLayout = next.toDto() }
                    },
                )
                openerSlot = WorkspaceFileOpener(
                    workspaceId = "w1",
                    treeOf = { sync.tree },
                    viewsOf = { provisional.toMap() + serverViews.toMap() },
                    edit = { transform -> sync.edit(transform) },
                    provisional = provisional,
                    reveal = { _, _, _ -> },
                    post = { id, state, groupId ->
                        delay(postLatencyMs)
                        val real = if (honoursClientId) id else "broker-" + id
                        withContext(NonCancellable) {
                            serverViews[real] = ViewDto(id = real, workspaceId = "w1", kind = "editor", state = state)
                            serverLayout = placeView(serverLayout.toDomainOrNull()!!, real, groupId).toDto()
                        }
                        real
                    },
                    scope = rememberCoroutineScope(),
                )

                val t = sync.tree
                SideEffect { if (drawn.lastOrNull() != t) drawn += t }
            }
            waitForIdle()
            openerSlot!!.open("b.kt", sourceViewId = startViews.keys.first())
            repeat(60) { mainClock.advanceTimeBy(100); waitForIdle() }
        }
        return drawn
    }

    private fun firstFileOpen(postLatencyMs: Long) = runOpen(
        start = LayoutNode.Group("g-tree", listOf("tree"), "tree"),
        startViews = mapOf("tree" to editorView("tree", "tree")),
        postLatencyMs = postLatencyMs,
    )

    /**
     * The REAL sequence, which the single-open cases above skip: the user makes the Files pane from
     * the "+" menu (broker-first — the broker mints the id and the client splits it out afterwards,
     * AppShell's addSlot), and then opens a file out of that tree [gapMs] later.
     *
     * Two layout edits are then in flight at once, composed into one `pending` function, and the
     * second one is the barrier-gated file open. Production lost the file's split with no refused
     * write at all, which none of the single-edit cases reproduce.
     */
    private fun addFilesThenOpen(postLatencyMs: Long, gapMs: Long): List<LayoutNode> {
        val drawn = java.util.concurrent.CopyOnWriteArrayList<LayoutNode>()
        runComposeUiTest {
            var serverLayout by mutableStateOf(LayoutNode.Group("g-chat", listOf("chat"), "chat").toDto())
            val serverViews = mutableStateMapOf<String, ViewDto>().also {
                it["chat"] = ViewDto(id = "chat", workspaceId = "w1", kind = "chat", state = JsonObject(emptyMap()))
            }
            var sync: WorkspaceLayoutState? = null

            setContent {
                val provisional = remember { mutableStateMapOf<String, ViewDto>() }
                provisional.keys.filter { it in serverViews }.forEach { provisional.remove(it) }
                val s = rememberWorkspaceLayout(
                    workspaceId = "w1",
                    serverLayout = serverLayout,
                    unconfirmedViews = provisional.keys.toSet(),
                    push = { next ->
                        delay(40)
                        validateLayout(next)?.let { error(it) }
                        collectViewIds(next).firstOrNull { it !in serverViews }?.let {
                            error("layout names a view that is not in this workspace: $it")
                        }
                        withContext(NonCancellable) { serverLayout = next.toDto() }
                    },
                )
                sync = s
                openerSlot = WorkspaceFileOpener(
                    workspaceId = "w1",
                    treeOf = { s.tree },
                    viewsOf = { provisional.toMap() + serverViews.toMap() },
                    edit = { transform -> s.edit(transform) },
                    provisional = provisional,
                    reveal = { _, _, _ -> },
                    post = { id, state, groupId ->
                        delay(postLatencyMs)
                        withContext(NonCancellable) {
                            serverViews[id] = ViewDto(id = id, workspaceId = "w1", kind = "editor", state = state)
                            serverLayout = placeView(serverLayout.toDomainOrNull()!!, id, groupId).toDto()
                        }
                        id
                    },
                    scope = rememberCoroutineScope(),
                )
                val t = s.tree
                SideEffect { if (drawn.lastOrNull() != t) drawn += t }
            }
            waitForIdle()

            // "+" → Files, SPLIT_RIGHT. Broker-first: it creates the view in g-chat and announces
            // it, THEN the client splits it out — exactly AppShell's addSlot callback.
            val treeId = "tree-view"
            serverViews[treeId] = editorView(treeId, "tree")
            serverLayout = placeView(serverLayout.toDomainOrNull()!!, treeId, "g-chat").toDto()
            waitForIdle()
            val filesGroup = "g-files"
            sync!!.edit { tree ->
                when (val owner = dev.supermux.workspace.groupIdOf(tree, treeId)) {
                    filesGroup, null -> tree
                    else -> dev.supermux.workspace.splitGroup(tree, owner, treeId, "row", filesGroup)
                }
            }
            repeat((gapMs / 50).toInt().coerceAtLeast(1)) { mainClock.advanceTimeBy(50); waitForIdle() }

            openerSlot!!.open("AGENTS.md", sourceViewId = treeId)
            repeat(60) { mainClock.advanceTimeBy(100); waitForIdle() }
        }
        return drawn
    }

    /** A SECOND file: it joins the group that already holds files, with no split to lose. */
    private fun secondFileOpen(postLatencyMs: Long) = runOpen(
        start = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("g-tree", listOf("tree"), "tree"),
                LayoutNode.Group("g-files", listOf("f1"), "f1"),
            ),
        ),
        startViews = mapOf("tree" to editorView("tree", "tree"), "f1" to editorView("f1", "file", "a.kt")),
        postLatencyMs = postLatencyMs,
    )

    /** The minted id is a UUID; find it as "the one that was not there at the start". */
    private fun fileId(drawn: List<LayoutNode>): String? {
        val startIds = collectViewIds(drawn.first()).toSet()
        return drawn.flatMap { collectViewIds(it) }.firstOrNull { it !in startIds }
    }

    /**
     * The open must be steady at EVERY latency, not just the fast one. Both failure shapes the
     * running app has shown live here: the same file in two tabs at once, and a tab that appears
     * and then vanishes before coming back somewhere else.
     */
    /**
     * A broker that ignores the client-minted id must not wedge the client.
     *
     * This is not hypothetical: the live broker ran for two days on code older than 3bfb400c, so
     * every open drew a tab for an id the broker had thrown away. What the user saw was the file in
     * TWO tabs — the broker's real one, plus ours, which could not be closed because
     * `DELETE /views/<our id>` 404s. Reported as "it first opens it in two tabs then closes one".
     *
     * The split is forfeit here (the file lands wherever the broker put it) and that is the
     * deliberate trade: against a broker we cannot talk to properly, degrade, do not wedge.
     */
    @Test
    fun aBrokerThatIgnoresOurMintedIdCostsTheSplitButNeverLeavesASecondTab() {
        val problems = mutableListOf<String>()
        for (post in listOf(60L, 600L)) {
            val drawn = runOpen(
                start = LayoutNode.Group("g-tree", listOf("tree"), "tree"),
                startViews = mapOf("tree" to editorView("tree", "tree")),
                postLatencyMs = post,
                honoursClientId = false,
            )
            val ours = drawn.flatMap { collectViewIds(it) }.filter { it != "tree" && !it.startsWith("broker-") }.toSet()
            val theirs = drawn.flatMap { collectViewIds(it) }.filter { it.startsWith("broker-") }.toSet()
            val last = collectViewIds(drawn.last())
            println("[skew] post=${post}ms last=$last ours=$ours theirs=$theirs")
            // Exactly one tab for the file, and it must be the broker's — ours is a tab nothing
            // can close.
            if (theirs.size != 1) problems += "post=$post: expected one broker view, got $theirs"
            val stranded = ours.filter { it in last }
            if (stranded.isNotEmpty()) problems += "post=$post: our un-closable tab survived: $stranded"
            if (last.count { it.startsWith("broker-") } != 1) {
                problems += "post=$post: the file is not in exactly one tab: $last"
            }
            drawn.forEach { t -> validateLayout(t)?.let { problems += "post=$post: invalid tree: $it" } }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /**
     * The file must land in its OWN pane beside the tree, whether or not the Files pane's own
     * layout edit has been confirmed yet. Production showed the file tabbed over the tree with no
     * refused write to explain it, which is the case this covers.
     */
    @Test
    fun theFirstFileKeepsItsSplitEvenRightAfterTheFilesPaneWasMade() {
        val problems = mutableListOf<String>()
        for (post in listOf(60L, 400L, 900L)) {
            // gap: how long after making the Files pane the user clicks a file. 50ms is inside the
            // 300ms debounce (the Files split is still unconfirmed); 800ms is after it landed.
            for (gap in listOf(50L, 800L)) {
                val drawn = addFilesThenOpen(post, gap)
                // NOT fileId(): this sequence adds `tree-view` mid-run too, and "the first id that
                // was not in the first frame" would find THAT and assert about the wrong view.
                val id = drawn.flatMap { collectViewIds(it) }.firstOrNull { it != "chat" && it != "tree-view" }
                if (id == null) { problems += "post=$post gap=$gap: the file never appeared"; continue }
                val shown = drawn.map { collectViewIds(it).count { v -> v == id } }
                val owner = dev.supermux.workspace.groupIdOf(drawn.last(), id)
                println("[seq] post=${post}ms gap=${gap}ms tabs=$shown owner=$owner last=${drawn.last()}")
                if (shown.any { it > 1 }) problems += "post=$post gap=$gap: two tabs at once ($shown)"
                if (shown.last() != 1) problems += "post=$post gap=$gap: settled on ${shown.last()} tabs"
                if (owner == "g-files") problems += "post=$post gap=$gap: tabbed over the tree — split lost"
                drawn.forEach { t -> validateLayout(t)?.let { problems += "post=$post gap=$gap: invalid: $it" } }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun openingAFileIsSteadyAtEveryLatency() {
        val problems = mutableListOf<String>()
        // Around, and well past, the 300ms layout debounce.
        for (latency in listOf(30L, 150L, 250L, 290L, 310L, 400L, 600L, 1200L)) {
            problems += check("first", latency, firstFileOpen(latency), landsIn = "g-tree")
            problems += check("second", latency, secondFileOpen(latency), landsIn = null)
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /**
     * @param landsIn a group the file must NOT end up in — for the first file that is the tree's
     *   own group, because being tabbed over the tree means the split was lost.
     */
    private fun check(
        which: String,
        latency: Long,
        drawn: List<LayoutNode>,
        landsIn: String?,
    ): List<String> {
        val problems = mutableListOf<String>()
        val id = fileId(drawn) ?: return listOf("$which post=${latency}ms: the file never appeared")
        val shown = drawn.map { collectViewIds(it).count { v -> v == id } }
        println("[open] $which post=${latency}ms tabs-per-frame=$shown last=${drawn.last()}")

        if (shown.any { it > 1 }) problems += "$which post=${latency}ms: drawn in two tabs at once ($shown)"
        // Appeared, then went away again: the tab flickers out from under the user.
        val firstSeen = shown.indexOfFirst { it > 0 }
        if (firstSeen >= 0 && shown.drop(firstSeen).any { it == 0 }) {
            problems += "$which post=${latency}ms: the tab appeared and then vanished ($shown)"
        }
        if (shown.last() != 1) problems += "$which post=${latency}ms: settled on ${shown.last()} tabs"
        if (landsIn != null && dev.supermux.workspace.groupIdOf(drawn.last(), id) == landsIn) {
            problems += "$which post=${latency}ms: the file ended up tabbed with the tree, its split lost"
        }
        drawn.forEach { t -> validateLayout(t)?.let { problems += "$which post=${latency}ms: invalid tree: $it" } }
        return problems
    }
}
