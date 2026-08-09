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
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
