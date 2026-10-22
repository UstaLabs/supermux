package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.session.SessionListScreen
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.workspace.ProjectRef
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * Headless design render of the sidebar (project and status views) (test-source only, never ships). Writes
 * `build/sidebar-styles/{dark,light}.png` plus one PNG per style — no window, runs over SSH.
 *
 *   ./gradlew :desktop:renderSidebarStyles
 */
fun main() {
    val out = File("build/sidebar-styles").apply { mkdirs() }
    val panels = listOf(true to "By project", false to "By status")
    for (mode in listOf(AppearanceMode.DARK, AppearanceMode.LIGHT)) {
        val tag = mode.name.lowercase()
        render(File(out, "$tag.png"), width = panels.size * 300 + (panels.size + 1) * 20, height = 900) {
            DesktopTheme(appearance = mode) {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    for ((byProject, label) in panels) Panel(byProject, label)
                }
            }
        }
    }
    println("wrote ${out.absolutePath}")
}

private fun render(file: File, width: Int, height: Int, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width * 2, height * 2, Density(2f), content = content)
    try {
        var t = 0L
        repeat(4) { scene.render(t); t += 16_666_667L }
        val img = scene.render(t)
        file.writeBytes(img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    } finally {
        scene.close()
    }
}

@Composable
private fun Panel(byProject: Boolean, label: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(300.dp)) {
        Text(label, color = cs.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(300.dp)
                .height(820.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)),
        ) { Sidebar(byProject) }
    }
}

@Composable
private fun Sidebar(byProject: Boolean) {
    val f = fixtures
    run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            SessionListScreen(
                home = "/home/u",
                activeId = "w-sidebar",
                onOpen = {},
                workspaces = f.workspaces,
                sessions = f.sessions,
                lastBySession = f.last,
                lastRead = f.lastRead,
                agentState = f.agent,
                runningAgents = mapOf("s-sidebar" to 2),
                onOpenSession = { _, _ -> },
                initialGroupByProject = byProject,
                archivedWorkspaces = f.archived,
                projects = f.projects,
                workspaceHost = { "h" },
                modifier = Modifier.size(300.dp, 820.dp),
            )
        }
    }
}

private class Fixtures(
    val workspaces: List<WorkspaceDto>,
    val sessions: List<SessionInfo>,
    val last: Map<String, LogEntry?>,
    val lastRead: Map<String, String>,
    val agent: Map<String, AgentStatus?>,
    val archived: List<WorkspaceDto>,
    val projects: List<ProjectRef>,
)

private val fixtures: Fixtures by lazy {
    val now = Instant.now()
    fun ago(min: Long) = now.minusSeconds(min * 60).toString()
    val ws = mutableListOf<WorkspaceDto>()
    val sessions = mutableListOf<SessionInfo>()
    val last = mutableMapOf<String, LogEntry?>()
    val read = mutableMapOf<String, String>()
    val agent = mutableMapOf<String, AgentStatus?>()

    fun add(
        id: String,
        name: String,
        repo: String,
        branch: String,
        minAgo: Long,
        unread: Boolean = false,
        working: Boolean = false,
        git: GitLiteStatusDto? = null,
        preview: String,
        chats: List<String> = listOf(name),
    ) {
        val repoRoot = "/home/u/projects/$repo"
        val sids = chats.indices.map { i -> if (i == 0) "s-${id.removePrefix("w-")}" else "s-${id.removePrefix("w-")}-$i" }
        val views = sids.mapIndexed { i, sid ->
            ViewDto(id = "v-$sid", workspaceId = id, kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive(sid))))
        }
        ws += WorkspaceDto(
            id = id, name = name, workdir = "/home/u/.mux/worktrees/$repo/$id", repoRoot = repoRoot, projectId = repo,
            branch = branch, views = views, primarySessionId = sids.first(), sortOrder = ws.size,
            layout = LayoutNodeDto.Group(id = "g-$id", viewIds = views.map { it.id }, activeViewId = views.first().id),
        )
        sids.forEachIndexed { i, sid ->
            sessions += SessionInfo(
                id = sid, name = chats[i], workdir = "/home/u/.mux/worktrees/$repo/$id", agent = if (i == 1) "codex" else "claude",
                repo_root = repoRoot, session_branch = branch, git = if (i == 0) git else null, status = "active",
            )
            val ts = ago(minAgo + i * 3)
            last[sid] = LogEntry(id = "m-$sid", ts = ts, direction = "out", text = preview)
            read[sid] = if (unread && i == 0) ago(minAgo + 30) else ts
            agent[sid] = AgentStatus(phase = if (working && i == 0) "running" else "idle", state = if (working && i == 0) "working" else "idle", working = working && i == 0)
        }
    }

    add("w-sidebar", "Sidebar redesign", "supermux", "mux/supermux-101", 1, working = true,
        preview = "Code for the 4 styles is in. Now writing a headless renderer…",
        chats = listOf("Sidebar redesign", "codex critique"),
        git = GitLiteStatusDto(ahead = 3, dirty = 4, touched = true))
    add("w-mermaid", "Mermaid dark-mode fills", "supermux", "mux/supermux-100", 14, unread = true,
        preview = "Done — text on authored fills now picks its contrast colour…",
        git = GitLiteStatusDto(ahead = 2, touched = true))
    add("w-proxy", "Proxy cap removal", "supermux", "mux/supermux-99", 62, unread = true,
        preview = "Should I also drop the per-host limit, or keep it for now?",
        git = GitLiteStatusDto(ahead = 1, dirty = 2, touched = true))
    add("w-worktrees", "Worktrees size calculation", "supermux", "mux/supermux-97", 190,
        preview = "The 50 s TTFB is mostly cold inode I/O, not CPU.")
    add("w-tradein", "Checkout trade-in step", "greenmate", "feat/tradein-checkout", 22, unread = true,
        preview = "Trade-in quote now persists across the cart and the payment step.",
        git = GitLiteStatusDto(ahead = 5, touched = true))
    add("w-oracle", "Oracle pool timeouts", "greenmate", "fix/oracle-pool", 2880,
        preview = "Pool max raised to 40; the 12.1 driver still leaks on cancel.")
    add("w-nuxt", "Nuxt 4 upgrade", "greenmate", "chore/nuxt-4", 7200,
        preview = "All pages build; two plugins still need the new runtime config.")
    add("w-kurban", "Hisse price table", "kurbanhane", "main", 45, working = true,
        preview = "Updating the 2027 share prices from the sheet…")

    val archived = buildList {
        repeat(12) { i -> add(WorkspaceDto(id = "a-s$i", name = "Old supermux task $i", workdir = "/home/u/.mux/worktrees/supermux/a$i", repoRoot = "/home/u/projects/supermux", projectId = "supermux", status = "archived", archivedAt = ago(5000L + i))) }
        repeat(4) { i -> add(WorkspaceDto(id = "a-g$i", name = "Old greenmate task $i", workdir = "/home/u/.mux/worktrees/greenmate/a$i", repoRoot = "/home/u/projects/greenmate", projectId = "greenmate", status = "archived", archivedAt = ago(5000L + i))) }
    }
    val projects = listOf("supermux" to "Supermux", "greenmate" to "Greenmate", "kurbanhane" to "Kurbanhane").mapIndexed { i, (id, name) ->
        ProjectRef("h", ProjectDto(id = id, name = name, sortOrder = i, locations = listOf(ProjectLocationDto("l-$id", "/home/u/projects/$id"))))
    }
    Fixtures(ws, sessions, last, read, agent, archived, projects)
}
