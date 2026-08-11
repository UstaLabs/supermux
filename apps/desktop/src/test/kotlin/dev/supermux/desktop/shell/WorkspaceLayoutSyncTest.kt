package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import dev.supermux.workspace.validateLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import dev.supermux.ui.panes.PaneHost

/**
 * The workspace layout round trip: local edit → debounced PATCH → the broker's
 * `workspace_changed` echo → adopt.
 *
 * ── Regression: a tab click in ONE pane must not move the OTHER pane ─────────
 *
 * Ahmet: "tabs have an interesting [problem], if i click a tab on a split it
 * also switches on anothers split as well".
 *
 * [WorkspaceLayoutState.dirty] is ONE flag for the WHOLE tree, and while it is
 * set every incoming frame is ignored — so the moment it clears, the next frame
 * replaces every group at once. Clearing it for a tree the broker never actually
 * took therefore does not lose one pane's selection: it leaves the broker
 * holding a stale tree that the next `workspace_changed` slams back over the
 * entire layout, including the panes the user never clicked in.
 *
 * These tests mount a real [PaneHost] over a Split with TWO Group children and
 * drive it through the same [rememberWorkspaceLayout] → `edit` → PATCH chain
 * AppShell uses, against a stand-in broker that behaves like a real one: a
 * request that has left the machine is applied whether or not this client is
 * still waiting for the response. Every other test in this package builds fresh
 * state and never round-trips at all, which is why they all passed while the app
 * was broken.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceLayoutSyncTest {

    /** Response latency of the stand-in broker — a relay round trip, not localhost. */
    private val brokerLatencyMs = 200L

    private fun twoPanes(): LayoutNodeDto = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("left", listOf("a", "b"), "a"),
            LayoutNode.Group("right", listOf("c", "d"), "c"),
        ),
    ).toDto()

    private fun LayoutNode.groups(): Pair<LayoutNode.Group, LayoutNode.Group> {
        val split = this as LayoutNode.Split
        return split.children[0] as LayoutNode.Group to split.children[1] as LayoutNode.Group
    }

    @Test
    fun aTabClickThatLandsWhileTheLastPatchIsInFlightStillReachesTheBroker() = runComposeUiTest {
        var server: LayoutNodeDto by mutableStateOf(twoPanes())
        var tree: LayoutNode? = null

        setContent {
            val sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = server,
                push = { next ->
                    // The PATCH has left the machine: the broker stores it and
                    // broadcasts, whether or not this client is still listening.
                    withContext(NonCancellable) { server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
            tree = sync.tree
            PaneHost(layout = sync.tree, onEdit = { edit -> sync.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        // Right pane → "d". Let the debounce fire so its PATCH is in flight, but
        // not the response: that is the window the next click lands in.
        onNodeWithTag("view-tab-d").performTouchInput { down(center); up() }
        mainClock.advanceTimeBy(LAYOUT_PATCH_DEBOUNCE_MS + 20)

        // Left pane → "b", while the right pane's write is still unanswered.
        onNodeWithTag("view-tab-b").performTouchInput { down(center); up() }
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        // Looking right on screen is not enough — a tree the broker never got is
        // a tree the next frame (or the next launch) silently undoes.
        assertEquals(tree, server.toDomainOrNull(), "the broker must end up holding what the UI shows")
    }

    @Test
    fun aPaneTheUserNeverClickedInDoesNotSwitchWhenTheBrokerNextSpeaks() = runComposeUiTest {
        // The user's symptom end to end. Once the client and the broker disagree,
        // the very next workspace_changed — a view added anywhere, an agent
        // renaming its session, another device — is adopted WHOLE, so a pane the
        // user has not touched jumps back to the view the broker still thinks is
        // active.
        var server: LayoutNodeDto by mutableStateOf(twoPanes())
        var tree: LayoutNode? = null

        setContent {
            val sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = server,
                push = { next ->
                    withContext(NonCancellable) { server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
            tree = sync.tree
            PaneHost(layout = sync.tree, onEdit = { edit -> sync.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        onNodeWithTag("view-tab-d").performTouchInput { down(center); up() }
        mainClock.advanceTimeBy(LAYOUT_PATCH_DEBOUNCE_MS + 20)
        onNodeWithTag("view-tab-b").performTouchInput { down(center); up() }
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        // An ordinary server-side layout change: the broker's addView appends to a
        // group and makes the new view active, then broadcasts workspace_changed.
        server = addViewToGroup(server.toDomainOrNull()!!, "right", "e").toDto()
        repeat(10) { mainClock.advanceTimeBy(200); waitForIdle() }

        val (left, right) = tree!!.groups()
        assertEquals("b", left.activeViewId, "the LEFT pane was never touched by the broker's change")
        assertEquals("e", right.activeViewId)
        onNodeWithText("body-b").assertIsDisplayed()
    }

    /**
     * A layout the broker REFUSES must be dropped, not replayed over every frame forever.
     *
     * Ahmet: "opening a file opens it twice… it opens two tabs, sometimes in the same view
     * group, sometimes in two different view groups."
     *
     * The unconfirmed edit is kept as a function and replayed onto every `workspace_changed`
     * frame. That is only safe while the edit is *outstanding*. [rememberWorkspaceLayout] has a
     * branch for "the broker refused this tree — fall through and let the next frame be
     * adopted", and that branch was UNREACHABLE: `BrokerApi.decode` turns every non-2xx into a
     * `CancellationException` (deliberately — a raw throw out of a SKIE-bridged suspend call
     * SIGABRTs the iOS process), and the effect read `CancellationException` as "a newer edit
     * restarted me" and rethrew, which KEEPS the pending edit.
     *
     * A refused write therefore pinned that edit for the rest of the session. Every frame from
     * then on was rebased through it, which is how a view the broker does not hold keeps coming
     * back, and how one that has since moved gets added a second time.
     *
     * The optimistic file open makes this easy to hit: the tab goes into the tree at once and
     * the `POST /views` that mints its row is still in flight, so a PATCH that overtakes it is
     * refused with `layout names a view that is not in this workspace` — which is exactly what
     * the running app logs.
     */
    @Test
    fun aRefusedLayoutIsDroppedInsteadOfBeingReplayedOverEveryFrame() = runComposeUiTest {
        val owned = setOf("a", "b")
        var server: LayoutNodeDto by mutableStateOf(LayoutNode.Group("g", listOf("a", "b"), "a").toDto())
        var refusals = 0
        var sync: WorkspaceLayoutState? = null

        setContent {
            val s = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = server,
                push = { next ->
                    if (collectViewIds(next).any { it !in owned }) {
                        refusals++
                        // How the real transport reports a 400, byte for byte: a
                        // CancellationException, thrown on a job nobody cancelled.
                        throw CancellationException("BrokerApi request unavailable")
                    }
                    withContext(NonCancellable) { server = next.toDto() }
                },
            )
            sync = s
            PaneHost(layout = s.tree, onEdit = { edit -> s.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        // The optimistic open: a tab whose view row the broker does not have (yet, or ever).
        runOnIdle { sync!!.edit { t -> addViewToGroup(t, "g", "ghost") } }
        repeat(5) { mainClock.advanceTimeBy(200); waitForIdle() }
        assertEquals(1, refusals, "the broker must have seen the write, and refused it")

        // Any later frame at all — a view added elsewhere, an agent renaming its session.
        server = LayoutNode.Group("g", listOf("a", "b"), "b").toDto()
        repeat(10) { mainClock.advanceTimeBy(200); waitForIdle() }

        assertEquals(
            listOf("a", "b"),
            collectViewIds(sync!!.tree),
            "a tab the broker refused must not be re-added to every frame that follows",
        )
        assertFalse(sync!!.dirty, "a write the broker refused is not still outstanding")
    }

    /**
     * The replay must never put one view in two groups. That is what "it opens two tabs"
     * looks like on screen: [PaneHost] draws a tab per view id per group, so one id in two
     * groups is the same file twice — and `normalizeLayout` does not dedupe, so nothing
     * downstream catches it.
     *
     * The shape below is the real one. `WorkspaceFileOpener` puts the tab in the group it
     * chose AT ONCE and POSTs after; the broker's own `addView` appends the row to whichever
     * group it can find, and it has never heard of a group this client made a moment ago. So
     * the frame comes back with the view somewhere else, and `addViewToGroup` — which only
     * checks the group it is told about — happily adds it a second time.
     */
    @Test
    fun anUnconfirmedEditThatWouldDuplicateAViewIsDroppedRatherThanDrawn() = runComposeUiTest {
        var server: LayoutNodeDto by mutableStateOf(twoPanes())
        var sync: WorkspaceLayoutState? = null

        setContent {
            val s = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = server,
                push = { next ->
                    // A real broker validates before it stores, and reports the refusal the
                    // way BrokerApi surfaces every non-2xx.
                    validateLayout(next)?.let { throw CancellationException("HTTP 400: $it") }
                    withContext(NonCancellable) { server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
            sync = s
            PaneHost(layout = s.tree, onEdit = { edit -> s.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        // The tab lands in the group the client picked, before the broker knows the view.
        runOnIdle { sync!!.edit { t -> addViewToGroup(t, "right", "e") } }
        waitForIdle()

        // …and the broker puts the very same view in a DIFFERENT group.
        server = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("left", listOf("a", "b", "e"), "e"),
                LayoutNode.Group("right", listOf("c", "d"), "c"),
            ),
        ).toDto()
        repeat(10) { mainClock.advanceTimeBy(200); waitForIdle() }

        assertNull(validateLayout(sync!!.tree), "the tree on screen must be one the broker could store")
        assertEquals(
            1,
            collectViewIds(sync!!.tree).count { it == "e" },
            "one open, one tab — got ${collectViewIds(sync!!.tree)}",
        )
        assertEquals(
            1,
            onAllNodesWithTag("view-tab-e").fetchSemanticsNodes().size,
            "the tab strip must show the view once",
        )
    }

    @Test
    fun aBrokerFrameIsAdoptedWhenNothingLocalIsPending() = runComposeUiTest {
        // The other direction must keep working: with no unconfirmed local edit,
        // another device's change lands here.
        var server: LayoutNodeDto by mutableStateOf(twoPanes())
        var tree: LayoutNode? = null

        setContent {
            val sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = server,
                push = { next -> withContext(NonCancellable) { server = next.toDto() } },
            )
            tree = sync.tree
            PaneHost(layout = sync.tree, onEdit = { edit -> sync.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        server = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("left", listOf("a", "b"), "b"),
                LayoutNode.Group("right", listOf("c", "d"), "d"),
            ),
        ).toDto()
        repeat(10) { mainClock.advanceTimeBy(200); waitForIdle() }

        val (left, right) = tree!!.groups()
        assertEquals("b", left.activeViewId)
        assertEquals("d", right.activeViewId)
    }
}
