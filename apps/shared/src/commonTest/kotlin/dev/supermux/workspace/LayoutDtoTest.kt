package dev.supermux.workspace

import dev.supermux.proto.LayoutNodeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutDtoTest {

    @Test
    fun aGroupRoundTrips() {
        val domain = LayoutNode.Group("g1", listOf("v1", "v2"), "v2")
        assertEquals(domain, domain.toDto().toDomain())
    }

    @Test
    fun aNestedSplitRoundTrips() {
        val domain = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Split("column", listOf(0.6, 0.4), listOf(
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                    LayoutNode.Group("g3", listOf("v3"), "v3"),
                )),
            ),
        )
        assertEquals(domain, domain.toDto().toDomain())
    }

    @Test
    fun aDtoGroupWithNoActiveViewGetsTheFirstOne() {
        // The broker always sends activeViewId, but an older row or a hand-edited
        // one might not. Fall back rather than carrying a null into the UI.
        val dto = LayoutNodeDto.Group(id = "g1", viewIds = listOf("v1", "v2"), activeViewId = null)
        assertEquals(LayoutNode.Group("g1", listOf("v1", "v2"), "v1"), dto.toDomain())
    }

    @Test
    fun anEmptyDtoGroupStaysEmptyWithNoActiveView() {
        val dto = LayoutNodeDto.Group(id = "g1", viewIds = emptyList(), activeViewId = null)
        assertEquals(LayoutNode.Group("g1", emptyList(), null), dto.toDomain())
    }

    @Test
    fun aNullDtoConvertsToNull() {
        assertNull((null as LayoutNodeDto?).toDomainOrNull())
    }
}
