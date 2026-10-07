package dev.supermux.workspace

import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private fun project(id: String, vararg paths: String, name: String = id, sortOrder: Int = 0) = ProjectDto(
    id = id,
    name = name,
    sortOrder = sortOrder,
    locations = paths.mapIndexed { i, p -> ProjectLocationDto("$id-l$i", p) },
)

class ProjectLaunchTest {

    @Test
    fun noLocationsNeedsOne() {
        assertEquals(LaunchLocation.NeedsLocation, launchLocation(project("p"), remembered = null))
        assertEquals(LaunchLocation.NeedsLocation, launchLocation(project("p"), remembered = "/a"))
    }

    @Test
    fun singleLocationIsChosenWhateverWasRemembered() {
        assertEquals(LaunchLocation.Chosen("/a"), launchLocation(project("p", "/a"), remembered = null))
        assertEquals(LaunchLocation.Chosen("/a"), launchLocation(project("p", "/a"), remembered = "/gone"))
    }

    @Test
    fun severalLocationsWithoutMemoryAsk() {
        assertEquals(
            LaunchLocation.Choose(listOf("/a", "/b")),
            launchLocation(project("p", "/a", "/b"), remembered = null),
        )
    }

    @Test
    fun rememberedLocationStillInTheProjectIsChosen() {
        assertEquals(LaunchLocation.Chosen("/b"), launchLocation(project("p", "/a", "/b"), remembered = "/b"))
    }

    @Test
    fun rememberedLocationNoLongerInTheProjectIsIgnored() {
        assertEquals(
            LaunchLocation.Choose(listOf("/a", "/b")),
            launchLocation(project("p", "/a", "/b"), remembered = "/moved-away"),
        )
    }

    @Test
    fun rememberedKeyIsHostQualified() {
        assertEquals("h1/p1", projectLocationKey("h1", "p1"))
        assertNotEquals(projectLocationKey("h1", "p1"), projectLocationKey("h2", "p1"))
    }

    @Test
    fun catalogOrdersBySortOrderThenNameThenId() {
        val list = listOf(
            project("z", name = "Beta", sortOrder = 1),
            project("b", name = "Alpha", sortOrder = 1),
            project("a", name = "Alpha", sortOrder = 1),
            project("c", name = "Zed", sortOrder = 0),
        )
        assertEquals(listOf("c", "a", "b", "z"), orderProjectCatalog(list).map { it.id })
    }

    @Test
    fun owningProjectMatchesExactLocationOnly() {
        val list = listOf(project("p1", "/home/u/app"), project("p2", "/home/u/app-two"))
        assertEquals("p2", projectOwning(list, "/home/u/app-two")?.id)
        assertEquals("p1", projectOwning(list, "/home/u/app")?.id)
        assertEquals(null, projectOwning(list, "/home/u/app/sub"))
    }
}
