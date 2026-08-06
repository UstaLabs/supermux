package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Design-approval preview for [WorkspaceListPanel]. Test-source only — never ships.
 *
 * Covers the cases that decide the row design:
 *  1. one chat view (common case — near today's session row)
 *  2. two chat views (child rows + multi-agent mark)
 *  3. chat + terminal + editor
 *  4. agent working (status dot / spinner)
 *  5. two projects (group headers)
 *  6. a long workspace name that must truncate
 *
 * Run (from apps/):
 *   ./gradlew :desktop:previewWorkspaceList
 *
 * Or without the helper task:
 *   ./gradlew :desktop:compileTestKotlin
 *   java -cp "$(./gradlew -q :desktop:printTestRuntimeCp)" \
 *        dev.supermux.desktop.shell.WorkspaceListPreviewKt
 */
fun main() = application {
    val home = "/home/u"
    val fixtures = previewFixtures()
    Window(
        onCloseRequest = ::exitApplication,
        title = "WorkspaceListPanel preview",
        state = rememberWindowState(width = 340.dp, height = 700.dp),
    ) {
        // Same theme wrapper Main.kt applies around the shell.
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                WorkspaceListPanel(
                    workspaces = fixtures.workspaces,
                    home = home,
                    activeId = "w-solo",
                    onOpen = {},
                    agentState = fixtures.agentState,
                    sessionNames = fixtures.sessionNames,
                    sessionRoles = fixtures.sessionRoles,
                    sessionGit = fixtures.sessionGit,
                    onOpenSession = { _, _ -> },
                    modifier = Modifier.width(340.dp).fillMaxSize(),
                )
            }
        }
    }
}

private data class PreviewFixtures(
    val workspaces: List<WorkspaceDto>,
    val agentState: Map<String, AgentStatus>,
    val sessionNames: Map<String, String>,
    val sessionRoles: Map<String, String?>,
    val sessionGit: Map<String, GitLiteStatusDto?>,
)

private fun chat(id: String, sessionId: String, wid: String) = ViewDto(
    id = id,
    workspaceId = wid,
    kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun view(id: String, wid: String, kind: String, title: String? = null) = ViewDto(
    id = id,
    workspaceId = wid,
    kind = kind,
    title = title,
)

private fun ws(
    id: String,
    name: String,
    workdir: String,
    repoRoot: String? = null,
    branch: String? = null,
    views: List<ViewDto> = emptyList(),
    primarySessionId: String? = null,
    sortOrder: Int = 0,
) = WorkspaceDto(
    id = id,
    name = name,
    workdir = workdir,
    repoRoot = repoRoot,
    branch = branch,
    views = views,
    primarySessionId = primarySessionId,
    sortOrder = sortOrder,
    layout = LayoutNodeDto.Group(
        id = "g-$id",
        viewIds = views.map { it.id },
        activeViewId = views.firstOrNull()?.id,
    ),
)

private fun previewFixtures(): PreviewFixtures {
    // 1. One chat — common case, must look close to today's session row.
    val solo = ws(
        id = "w-solo",
        name = "Fix Renaming",
        workdir = "/home/u/.mux/worktrees/fix-renaming",
        repoRoot = "/home/u/projects/supermux",
        branch = "mux/fix-renaming",
        views = listOf(chat("v-solo", "s-solo", "w-solo")),
        primarySessionId = "s-solo",
        sortOrder = 0,
    )

    // 2. Two chats — child rows + multi-agent mark.
    val multi = ws(
        id = "w-multi",
        name = "Shared review",
        workdir = "/home/u/.mux/worktrees/shared-review",
        repoRoot = "/home/u/projects/supermux",
        branch = "mux/shared-review",
        views = listOf(
            chat("v-m1", "s-m1", "w-multi"),
            chat("v-m2", "s-m2", "w-multi"),
        ),
        primarySessionId = "s-m1",
        sortOrder = 1,
    )

    // 3. Chat + terminal + editor (one chat → no children; three view kinds).
    val mixed = ws(
        id = "w-mixed",
        name = "Layout experiment",
        workdir = "/home/u/.mux/worktrees/layout-exp",
        repoRoot = "/home/u/projects/supermux",
        branch = "mux/layout-exp",
        views = listOf(
            chat("v-c", "s-mixed", "w-mixed"),
            view("v-t", "w-mixed", "terminal", title = "term"),
            view("v-e", "w-mixed", "editor", title = "editor"),
        ),
        primarySessionId = "s-mixed",
        sortOrder = 2,
    )

    // 4. Agent working — status rail spinner (teal).
    val working = ws(
        id = "w-work",
        name = "Ship the sidebar",
        workdir = "/home/u/projects/other",
        repoRoot = "/home/u/projects/other",
        branch = "main",
        views = listOf(chat("v-w", "s-work", "w-work")),
        primarySessionId = "s-work",
        sortOrder = 0,
    )

    // 6. Long name that must truncate (same second project as working).
    val longName = ws(
        id = "w-long",
        name = "Extremely long workspace name that must truncate in the sidebar row without wrapping",
        workdir = "/home/u/projects/other",
        repoRoot = "/home/u/projects/other",
        branch = "feature/very-long-branch-name-that-also-truncates",
        views = listOf(chat("v-l", "s-long", "w-long")),
        primarySessionId = "s-long",
        sortOrder = 1,
    )

    val agentState = mapOf(
        "s-solo" to AgentStatus(phase = "idle", working = false),
        "s-m1" to AgentStatus(phase = "idle", working = false),
        "s-m2" to AgentStatus(phase = "idle", working = false),
        "s-mixed" to AgentStatus(phase = "idle", working = false),
        "s-work" to AgentStatus(phase = "running", working = true),
        "s-long" to AgentStatus(phase = "idle", working = false),
    )

    val sessionNames = mapOf(
        "s-solo" to "Fix Renaming",
        "s-m1" to "claude review",
        "s-m2" to "codex critique",
        "s-mixed" to "Layout experiment",
        "s-work" to "Ship the sidebar",
        "s-long" to "long name agent",
    )

    val sessionGit = mapOf(
        "s-solo" to GitLiteStatusDto(mode = "base", ahead = 2, behind = 0, dirty = 1, touched = true),
        "s-m1" to GitLiteStatusDto(mode = "base", ahead = 0, behind = 0, dirty = 0, touched = false),
        "s-m2" to GitLiteStatusDto(mode = "base", ahead = 0, behind = 0, dirty = 0, touched = false),
        "s-mixed" to GitLiteStatusDto(mode = "base", ahead = 1, behind = 0, dirty = 0, touched = true),
        "s-work" to GitLiteStatusDto(mode = "base", ahead = 3, behind = 1, dirty = 2, touched = true),
        "s-long" to GitLiteStatusDto(mode = "base", ahead = 0, behind = 0, dirty = 0, touched = false),
    )

    return PreviewFixtures(
        workspaces = listOf(solo, multi, mixed, working, longName),
        agentState = agentState,
        sessionNames = sessionNames,
        sessionRoles = emptyMap(), // no PAs in the fixture
        sessionGit = sessionGit,
    )
}
