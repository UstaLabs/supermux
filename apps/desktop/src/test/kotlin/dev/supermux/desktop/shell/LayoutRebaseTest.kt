package dev.supermux.desktop.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.removeViewFromLayout
import dev.supermux.workspace.setSplitSizes
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.toDto
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.supermux.ui.panes.PaneHost

/**
 * A local layout edit is an OFFSET from the broker's tree, not a replacement for
 * it. A frame that lands while an edit is unconfirmed must be rebased onto, never
 * dropped.
 *
 * ── Regression: the layout rolls back when you resize ────────────────────────
 *
 * Ahmet: "i closed a terminal then it closed the split (normal bc it was the
 * last one), but when i resized the split came back with a tab called 'view',
 * also some i created another terminal then resized another panel, it
 * disappeared again ... i think there is an archictectural problem and a problem
 * with state updating".
 *
 * He was right about where it lives. The sync layer used to treat local and
 * server state as alternatives — `if (!dirty) adopt(server)` — so every
 * `workspace_changed` that arrived during the ~300ms debounce was DISCARDED. The
 * broker deleting a view, or confirming a new one, could therefore never be
 * seen: by the time the flag cleared, that frame was gone and no other was
 * coming. The client then PATCHed its pre-delete tree back over the broker's, so
 * a deleted view was persisted again and drawn as a tab titled "view" — the
 * [PaneHost] fallback for a view id with no record behind it.
 *
 * The stand-in broker below behaves like the real one: it stores what it is
 * sent, and a change made on the server side (a delete, another device) is
 * announced whether or not this client happens to be mid-edit.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutRebaseTest {

    private val brokerLatencyMs = 200L

    private fun twoPanes(): LayoutNode = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("left", listOf("a"), "a"),
            LayoutNode.Group("right", listOf("b"), "b"),
        ),
    )

    /** Drives one workspace's sync exactly as AppShell does. */
    private class Harness {
        var server: LayoutNodeDto by mutableStateOf(LayoutNodeDto.Group("g", emptyList(), null))
        var sync: WorkspaceLayoutState? = null
        val tree: LayoutNode get() = sync!!.tree
    }

    @Test
    fun aViewDeletedWhileAResizeIsPendingStaysDeleted() = runComposeUiTest {
        val h = Harness()
        h.server = twoPanes().toDto()

        setContent {
            h.sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = h.server,
                push = { next ->
                    withContext(NonCancellable) { h.server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
        }
        waitForIdle()

        // 1. Resize. The PATCH is now debouncing: dirty is set.
        h.sync!!.edit { setSplitSizes(it, emptyList(), listOf(0.7, 0.3)) }
        waitForIdle()

        // 2. The broker deletes a view (the user closed a terminal) and announces
        //    the collapsed tree, while our resize is still unconfirmed.
        h.server = removeViewFromLayout(h.server.toDomainOrNull()!!, "a")!!.toDto()
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        // The delete must survive. It used to not: the frame was dropped for being
        // mid-edit, and the next PATCH wrote "a" back to the broker.
        assertFalse("a" in collectViewIds(h.tree), "a deleted view must not survive a pending resize, got ${h.tree}")
        assertFalse(
            "a" in collectViewIds(h.server.toDomainOrNull()!!),
            "and it must not be resurrected in the broker's copy either, got ${h.server.toDomainOrNull()}",
        )
    }

    @Test
    fun aTabClickedInTheUiRebasesToo() = runComposeUiTest {
        // End to end, through the real PaneHost, because the sync layer only
        // rebases what reaches it AS A FUNCTION. Wiring PaneHost's tree-shaped
        // callback to `edit { thatTree }` type-checks, passes every test that
        // drives WorkspaceLayoutState directly, and silently defeats the whole
        // mechanism: a constant function ignores the server frame it is replayed
        // on and hands back the tree it closed over. Only clicking a real tab and
        // then having the broker speak catches that.
        val h = Harness()
        h.server = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("left", listOf("a"), "a"),
                LayoutNode.Group("right", listOf("b", "c"), "b"),
            ),
        ).toDto()

        setContent {
            val sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = h.server,
                push = { next ->
                    withContext(NonCancellable) { h.server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
            h.sync = sync
            PaneHost(layout = sync.tree, onEdit = { edit -> sync.edit(edit) }) { Text("body-$it") }
        }
        waitForIdle()

        // Click "c" in the right pane, then have the broker delete "a" from the
        // left one while that click is still unconfirmed.
        onNodeWithTag("view-tab-c").performTouchInput { down(center); up() }
        waitForIdle()
        h.server = removeViewFromLayout(h.server.toDomainOrNull()!!, "a")!!.toDto()
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        assertFalse("a" in collectViewIds(h.tree), "the broker's delete must win, got ${h.tree}")
        val group = h.tree as LayoutNode.Group
        assertEquals("c", group.activeViewId, "and the click must survive being replayed onto it")
    }

    @Test
    fun theResizeItselfIsNotLostWhenTheServerSpeaksMidEdit() = runComposeUiTest {
        // Rebasing must keep the user's edit, not just the server's truth. The
        // resize targets the root split by path, and the root split still exists
        // after an unrelated view is added, so it must still be applied.
        val h = Harness()
        h.server = twoPanes().toDto()

        setContent {
            h.sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = h.server,
                push = { next ->
                    withContext(NonCancellable) { h.server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
        }
        waitForIdle()

        h.sync!!.edit { setSplitSizes(it, emptyList(), listOf(0.7, 0.3)) }
        waitForIdle()
        // Another device adds a view mid-edit.
        h.server = addViewToGroup(h.server.toDomainOrNull()!!, "right", "z").toDto()
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        val split = h.tree as LayoutNode.Split
        assertEquals(0.7, split.sizes[0], 1e-6, "the user's resize must survive the rebase")
        assertTrue("z" in collectViewIds(h.tree), "and the server's new view must arrive")
    }

    @Test
    fun aViewAddedLocallyIsNotDroppedByTheNextFrame() = runComposeUiTest {
        // The mirror image of the first test, and Ahmet's second symptom: a view
        // this client just placed must not vanish when the broker next speaks.
        val h = Harness()
        h.server = twoPanes().toDto()

        setContent {
            h.sync = rememberWorkspaceLayout(
                workspaceId = "ws1",
                serverLayout = h.server,
                push = { next ->
                    withContext(NonCancellable) { h.server = next.toDto() }
                    delay(brokerLatencyMs)
                },
            )
        }
        waitForIdle()

        // The broker created the view and this client placed it in a group.
        h.sync!!.edit { addViewToGroup(it, "right", "d") }
        waitForIdle()
        // An unrelated frame lands mid-edit — an agent renaming its session, say.
        h.server = addViewToGroup(h.server.toDomainOrNull()!!, "left", "e").toDto()
        repeat(20) { mainClock.advanceTimeBy(200); waitForIdle() }

        assertTrue("d" in collectViewIds(h.tree), "the locally placed view must survive, got ${h.tree}")
        assertTrue("e" in collectViewIds(h.tree), "and so must the server's, got ${h.tree}")
        assertEquals(
            collectViewIds(h.tree).toSet(),
            collectViewIds(h.server.toDomainOrNull()!!).toSet(),
            "client and broker must converge",
        )
    }
}
