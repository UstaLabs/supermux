package dev.supermux.android.workspace

import dev.supermux.android.host.workspaceForSession
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.WorkspaceKeepAliveCache
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceScreenModelTest {
    private fun splitLayout(): LayoutNode = LayoutNode.Split(
        direction = "row",
        sizes = listOf(0.5, 0.5),
        children = listOf(
            LayoutNode.Group("g1", listOf("chat", "term"), "chat"),
            LayoutNode.Group("g2", listOf("files"), "files"),
        ),
    )

    @Test fun phoneTabsFollowCollectViewIdsOrder() {
        val model = phoneTabModel(splitLayout(), activeViewId = "term")
        assertEquals(listOf("chat", "term", "files"), model.viewIds)
        assertEquals("term", model.selectedId)
    }

    @Test fun phoneSelectedFallsBackToFirstWhenActiveMissing() {
        val model = phoneTabModel(splitLayout(), activeViewId = "gone")
        assertEquals("chat", model.selectedId)
    }

    @Test fun phoneSelectedFallsBackWhenActiveNull() {
        val model = phoneTabModel(splitLayout(), activeViewId = null)
        assertEquals("chat", model.selectedId)
    }

    @Test fun emptyLayoutHasNoSelection() {
        val model = phoneTabModel(LayoutNode.Group("g", emptyList()), activeViewId = "x")
        assertEquals(emptyList(), model.viewIds)
        assertNull(model.selectedId)
    }

    @Test fun activeViewPatchBodyNeverContainsLayout() {
        val body = activeViewPatchBody("v1")
        assertNull(body.layout)
        assertEquals("v1", body.activeViewId)
        assertNull(body.name)
    }

    @Test fun addViewStatePerKind() {
        val term = addViewState(NewViewKind.TERMINAL, nowMillis = 1234567890L)
        assertEquals(JsonPrimitive("workspace"), term["scope"])
        assertEquals(JsonPrimitive("t567890"), term["terminalId"])
        assertEquals(JsonPrimitive("tree"), addViewState(NewViewKind.EDITOR)["mode"])
        assertEquals(JsonPrimitive("diff"), addViewState(NewViewKind.DIFF)["mode"])
        assertEquals(JsonPrimitive(""), addViewState(NewViewKind.DISPLAY)["displayId"])
        assertTrue(addViewState(NewViewKind.CHAT).isEmpty())
    }

    @Test fun phoneAddKindsExcludeChat() {
        assertEquals(
            listOf(NewViewKind.TERMINAL, NewViewKind.EDITOR, NewViewKind.DIFF, NewViewKind.DISPLAY),
            phoneAddKinds(),
        )
    }

    @Test fun closeNeedsConfirmTerminal() {
        assertTrue(closeNeedsConfirm("terminal"))
    }

    @Test fun closeNeedsConfirmDisplay() {
        assertTrue(closeNeedsConfirm("display"))
    }

    @Test fun closeNeedsConfirmChatFalse() {
        assertFalse(closeNeedsConfirm("chat"))
    }

    @Test fun closeNeedsConfirmEditorFalse() {
        assertFalse(closeNeedsConfirm("editor"))
    }

    @Test fun closeNeedsConfirmViewDtoUsesKind() {
        assertTrue(closeNeedsConfirm(ViewDto(id = "t", workspaceId = "w", kind = "terminal")))
    }

    @Test fun keepAliveIsKeyedByWorkspaceAndIncludesActive() {
        val cache = WorkspaceKeepAliveCache(maxSize = 3)
        val live = (1..5).map { "w$it" }.toSet()
        cache.update("w1", live)
        cache.update("w2", live)
        cache.update("w3", live)
        val kept = cache.update("w5", live)
        assertTrue("w5" in kept)
        assertEquals(3, kept.size)
        val switched = cache.update("w4", live)
        assertTrue("w4" in switched)
        assertTrue("w5" in switched)
    }

    @Test fun chatActivationFirstSelectionActivates() {
        val chat = ViewDto(id = "chat", workspaceId = "w1", kind = "chat")
        val ws = WorkspaceDto(id = "w1", name = "one", workdir = "/", views = listOf(chat), activeViewId = "editor")
        assertEquals(
            ChatActivationHandle.ApplyConsume,
            chatActivationDecision("s1", lastActivated = null, ws, chat),
        )
    }

    @Test fun chatActivationSameSelectionAfterWorkspaceChangeDoesNotActivate() {
        val chat = ViewDto(id = "chat", workspaceId = "w1", kind = "chat")
        val ws = WorkspaceDto(id = "w1", name = "one", workdir = "/", views = listOf(chat), activeViewId = "editor")
        assertEquals(
            ChatActivationHandle.Skip,
            chatActivationDecision("s1", lastActivated = "s1", ws, chat),
        )
    }

    @Test fun chatActivationNewSelectionActivates() {
        val chat = ViewDto(id = "chat", workspaceId = "w1", kind = "chat")
        val ws = WorkspaceDto(id = "w1", name = "one", workdir = "/", views = listOf(chat), activeViewId = "editor")
        assertEquals(
            ChatActivationHandle.ApplyConsume,
            chatActivationDecision("s2", lastActivated = "s1", ws, chat),
        )
    }

    @Test fun chatActivationRetriesWhenWorkspacesEmpty() {
        assertEquals(
            ChatActivationHandle.ApplyRetry,
            chatActivationDecision("s1", lastActivated = null, ws = null, chatView = null),
        )
    }

    @Test fun chatActivationSkipsBlankSelection() {
        val chat = ViewDto(id = "chat", workspaceId = "w1", kind = "chat")
        val ws = WorkspaceDto(id = "w1", name = "one", workdir = "/", views = listOf(chat))
        assertEquals(ChatActivationHandle.Skip, chatActivationDecision(null, null, ws, chat))
        assertEquals(ChatActivationHandle.Skip, chatActivationDecision("", null, ws, chat))
    }

    @Test fun workspaceForSessionFindsChatOwner() {
        val chat = ViewDto(
            id = "v1",
            workspaceId = "w1",
            kind = "chat",
            state = kotlinx.serialization.json.buildJsonObject {
                put("sessionId", JsonPrimitive("s1"))
            },
        )
        val ws = WorkspaceDto(id = "w1", name = "one", workdir = "/", views = listOf(chat))
        assertEquals("w1", workspaceForSession(listOf(ws), "s1")?.id)
        assertNull(workspaceForSession(listOf(ws), "s-missing"))
    }
}
