package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.projectGroupKey
import kotlin.test.Test
import dev.supermux.proto.SessionInfo

/** The Windows host from the report (DESKTOP-OMQ5AMQ): three folders, `\` paths, home under C:\Users. */
internal object WindowsHost {
    const val HOST = "h1"
    const val HOME = "C:\\Users\\hatic"

    /**
     * Folder → the project name the host gives it (its last segment) and its live workspaces, in
     * the order the host's startup backfill files them (by name, code-unit order).
     */
    val folders = listOf(
        Triple("D:\\UnrealProject\\TestProject", "TestProject", 1),
        Triple("D:\\Projects\\TunnelWho", "TunnelWho", 1),
        Triple("C:\\Users\\hatic\\.mux\\state\\projects\\github.com\\yasinemre\\erydor", "erydor", 3),
    )

    val projects = folders.mapIndexed { i, (path, name, _) ->
        ProjectRef(HOST, ProjectDto(id = "p$i", name = name, sortOrder = i, locations = listOf(ProjectLocationDto("l$i", path))))
    }

    /** [filed]: the host matched each folder to its project (fixed host) — or left it bare (old host). */
    fun workspaces(filed: Boolean): List<WorkspaceDto> = folders.flatMapIndexed { i, (path, _, count) ->
        (1..count).map { n ->
            WorkspaceDto(
                id = "w$i-$n", name = "Session $n", workdir = path, projectId = if (filed) "p$i" else null,
                layout = LayoutNodeDto.Group(id = "g", viewIds = emptyList()),
            )
        }
    }

    fun projectKey(i: Int) = projectGroupKey(HOST, "p$i")
}

/** The sidebar as a phone draws it: touch, no mouse. */
@Composable
internal fun WindowsHostSidebar(filed: Boolean, collapsed: Boolean = false) {
    CompositionLocalProvider(LocalInputMode provides InputMode.Touch, LocalPointerAvailable provides false) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            val workspaces = WindowsHost.workspaces(filed)
            SessionListScreen(
                home = WindowsHost.HOME,
                activeId = null,
                onOpen = {},
                workspaces = workspaces,
                projects = if (filed) WindowsHost.projects else emptyList(),
                workspaceHost = { WindowsHost.HOST },
                onNavigate = {},
                initialCollapsedPaths = if (!collapsed) emptySet() else if (filed) {
                    WindowsHost.folders.indices.map(WindowsHost::projectKey).toSet()
                } else {
                    WindowsHost.folders.map { it.first }.toSet()
                },
            )
        }
    }
}

@OptIn(ExperimentalTestApi::class)
class WindowsHostSidebarTest {
    @Test fun everyProjectRowOfAWindowsHostHasItsMenu() = runComposeUiTest {
        setContent { WindowsHostSidebar(filed = true, collapsed = true) }
        WindowsHost.folders.forEachIndexed { i, (_, name, _) ->
            onNodeWithText(name).assertIsDisplayed()
            onNodeWithTag(ProjectTestIds.menu(WindowsHost.projectKey(i))).assertIsDisplayed()
        }
    }

    @Test fun aWindowsHostsUnfiledFoldersAreNamedByTheirLastSegment() = runComposeUiTest {
        setContent { WindowsHostSidebar(filed = false, collapsed = true) }
        WindowsHost.folders.forEach { (path, name, _) ->
            onNodeWithText(name).assertIsDisplayed()
            onNodeWithText(path).assertDoesNotExist()
        }
    }

    // An Agent's folder is not a project. A Windows Agent's workdir has no `/`: its parent was never found, and every
    // Agent showed as an empty project row.
    @Test fun aWindowsAgentsFolderIsNotAProject() = runComposeUiTest {
        val agentDir = "C:\\Users\\hatic\\.mux\\workspace\\home"
        val agentProject = ProjectRef(
            WindowsHost.HOST,
            ProjectDto(id = "pa", name = "AgentFolder", sortOrder = 9, locations = listOf(ProjectLocationDto("la", agentDir))),
        )
        setContent {
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch, LocalPointerAvailable provides false) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    SessionListScreen(
                        home = WindowsHost.HOME,
                        activeId = null,
                        onOpen = {},
                        workspaces = WindowsHost.workspaces(filed = true),
                        sessions = listOf(SessionInfo(id = "s-pa", name = "Home", workdir = agentDir, agent = "claude", role = "personal_assistant")),
                        projects = WindowsHost.projects + agentProject,
                        workspaceHost = { WindowsHost.HOST },
                        onNavigate = {},
                    )
                }
            }
        }
        onNodeWithText("TunnelWho").assertIsDisplayed()
        onNodeWithText("AgentFolder").assertDoesNotExist()
    }
}
