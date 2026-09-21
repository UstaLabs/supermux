package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

class ProjectFramesTest {

    @Test
    fun decodesProjectsChanged() {
        val frame = json.decodeFromString<ServerFrame>(
            """{"type":"projects_changed","projects":[{"id":"p","name":"A","sort_order":0,"created_at":"t","locations":[{"id":"l","path":"/a"}]}],"projectMembership":{"w1":"p"}}""",
        )
        assertTrue(frame is ServerFrame.ProjectsChanged)
        val p = frame.projects.single()
        assertEquals("p", p.id)
        assertEquals("A", p.name)
        assertNull(p.imageId)
        assertEquals(0, p.sortOrder)
        assertEquals("t", p.createdAt)
        assertEquals(listOf(ProjectLocationDto(id = "l", path = "/a")), p.locations)
        assertEquals(mapOf("w1" to "p"), frame.projectMembership)
    }

    @Test
    fun decodesProjectImageId() {
        val p = json.decodeFromString<ProjectDto>(
            """{"id":"p","name":"A","image_id":"x.png","sort_order":3,"created_at":"t","locations":[]}""",
        )
        assertEquals("x.png", p.imageId)
        assertEquals(3, p.sortOrder)
    }

    @Test
    fun snapshotWithoutProjectsDecodesEmpty() {
        val frame = json.decodeFromString<ServerFrame>("""{"type":"snapshot","sessions":[]}""")
        frame as ServerFrame.Snapshot
        assertEquals(emptyList(), frame.projects)
        assertEquals(emptyMap(), frame.projectMembership)
    }

    @Test
    fun snapshotCarriesProjectsAndMembership() {
        val frame = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","projects":[{"id":"p","name":"A"}],"projectMembership":{"w1":"p"}}""",
        )
        frame as ServerFrame.Snapshot
        assertEquals(listOf("p"), frame.projects.map { it.id })
        assertEquals(mapOf("w1" to "p"), frame.projectMembership)
    }

    @Test
    fun workspaceDtoProjectIdIsOptional() {
        val bare = json.decodeFromString<WorkspaceDto>("""{"id":"w","name":"n","workdir":"/w"}""")
        assertNull(bare.projectId)
        val withId = json.decodeFromString<WorkspaceDto>("""{"id":"w","name":"n","workdir":"/w","project_id":"p"}""")
        assertEquals("p", withId.projectId)
    }
}
