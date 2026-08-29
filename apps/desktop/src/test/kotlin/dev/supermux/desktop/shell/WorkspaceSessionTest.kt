package dev.supermux.desktop.shell

import dev.supermux.proto.ViewDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WorkspaceSessionTest {
    @Test
    fun mergePrefersServerOnCollision() {
        val provisional = mapOf(
            "v1" to ViewDto(id = "v1", workspaceId = "w", kind = "editor", title = "stand-in"),
            "v2" to ViewDto(id = "v2", workspaceId = "w", kind = "editor", title = "only-local"),
        )
        val server = mapOf(
            "v1" to ViewDto(id = "v1", workspaceId = "w", kind = "editor", title = "broker"),
        )
        val merged = mergeWorkspaceViews(provisional, server)
        assertEquals("broker", merged["v1"]?.title)
        assertEquals("only-local", merged["v2"]?.title)
    }

    @Test
    fun mergeReturnsServerWhenNoProvisional() {
        val server = mapOf("v1" to ViewDto(id = "v1", workspaceId = "w", kind = "chat"))
        assertSame(server, mergeWorkspaceViews(emptyMap(), server))
    }
}
