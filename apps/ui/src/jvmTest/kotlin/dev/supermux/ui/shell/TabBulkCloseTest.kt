package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun view(id: String, kind: String, state: Map<String, String> = emptyMap()) = ViewDto(
    id = id, workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

class TabBulkCloseTest {
    private val strip = listOf("a", "b", "c", "d")

    @Test
    fun targetsAreRelativeToTheAnchor() {
        assertEquals(listOf("c", "d"), bulkCloseTargets(strip, "b", BulkClose.RIGHT))
        assertEquals(listOf("a"), bulkCloseTargets(strip, "b", BulkClose.LEFT))
        assertEquals(strip, bulkCloseTargets(strip, "b", BulkClose.ALL))
        assertEquals(emptyList(), bulkCloseTargets(strip, "zz", BulkClose.ALL))
    }

    @Test
    fun entriesThatWouldCloseNothingAreLeftOut() {
        assertEquals(listOf("Close to the Left", "Close All"), bulkCloseEntries(strip, "d") { _, _ -> }.map { it.label })
        assertEquals(listOf("Close to the Right", "Close All"), bulkCloseEntries(strip, "a") { _, _ -> }.map { it.label })
        assertEquals(listOf("Close All"), bulkCloseEntries(listOf("a"), "a") { _, _ -> }.map { it.label })
    }

    @Test
    fun theStripIsTheAnchorsOwnGroup() {
        val tree = LayoutNode.Split(
            direction = "row", sizes = listOf(0.5, 0.5),
            children = listOf(
                LayoutNode.Group(id = "g1", viewIds = listOf("a", "b"), activeViewId = "a"),
                LayoutNode.Group(id = "g2", viewIds = listOf("c", "d"), activeViewId = "c"),
            ),
        )
        assertEquals(listOf("c", "d"), groupViewIdsOf(tree, "d"))
        assertEquals(emptyList(), groupViewIdsOf(tree, "zz"))
    }

    @Test
    fun theQueueHandsOutOneLiveTabAtATime() {
        val views = mapOf("a" to view("a", "editor"), "c" to view("c", "terminal"))
        val queue = mutableListOf("a", "gone", "c")
        assertEquals("a", nextBulkClose(queue) { views[it] }?.id)
        // "gone" was closed elsewhere meanwhile: skipped, not handed out.
        assertEquals("c", nextBulkClose(queue) { views[it] }?.id)
        assertNull(nextBulkClose(queue) { views[it] })
        assertTrue(queue.isEmpty())
    }
}
