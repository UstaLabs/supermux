package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.PathValidation
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.ProjectLocationResult
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.workspace.projectLocationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

private const val HOST = "h1"

private fun project(id: String, name: String, vararg paths: String, sortOrder: Int = 0) = ProjectDto(
    id = id,
    name = name,
    sortOrder = sortOrder,
    locations = paths.mapIndexed { i, p -> ProjectLocationDto("$id-l$i", p) },
)

/**
 * Persistent Projects Task 10: the launcher picks a PROJECT, then (only when needed) one of its
 * locations — and keeps today's path picker when the host has no catalog, and inside a workspace
 * tab.
 */
@OptIn(ExperimentalTestApi::class)
class SessionLauncherProjectsTest {

    @Composable
    private fun Harness(
        catalog: Flow<List<ProjectDto>>,
        prefs: LauncherPrefs = LauncherPrefs(),
        onPrefsChange: (LauncherPrefs) -> Unit = {},
        validate: (String) -> PathValidation? = { null },
        addLocation: (String, String) -> ProjectLocationResult = { _, _ -> ProjectLocationResult.Failed },
        workspaceWorkdir: String? = null,
        initialProjectId: String? = null,
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            SessionLauncherScreen(
                sessions = emptyList(),
                home = "/home/u",
                onBack = {},
                actions = LauncherActions(
                    projectCatalog = catalog,
                    validatePath = { validate(it) },
                    addProjectLocation = { id, path -> addLocation(id, path) },
                ),
                loadPrefs = { prefs },
                onPrefsChange = onPrefsChange,
                onSubmit = { _, _, _, _, _, _, _, _, _ -> null },
                workspaceWorkdir = workspaceWorkdir,
                selectedHost = HOST,
                initialProjectHost = initialProjectId?.let { HOST },
                initialProjectId = initialProjectId,
            )
        }
    }

    private fun ComposeUiTest.pointer(content: @Composable () -> Unit) = setPlatformContent(
        platform = FakePlatform(),
        pointer = true,
        widthClass = WindowWidthClass.Expanded,
        inputMode = InputMode.Pointer,
        content = content,
    )

    private fun ComposeUiTest.caption(text: String) =
        onNodeWithTag("launcher_workdir_caption").assertTextEquals(text)

    @Test fun no_catalog_keeps_the_path_omnibox() = runComposeUiTest {
        pointer { Harness(catalog = flowOf(emptyList())) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag("launcher_project_menu").assertExists()
        onNodeWithTag(CatalogPickerTestIds.MENU).assertDoesNotExist()
    }

    @Test fun catalog_lists_projects_in_order_and_a_single_location_is_chosen() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        val catalog = listOf(
            project("b", "Beta", "/home/u/beta", sortOrder = 1),
            project("a", "Alpha", "/home/u/alpha", sortOrder = 0),
        )
        pointer { Harness(catalog = flowOf(catalog), onPrefsChange = { saved = it }) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("a")).assertIsDisplayed()
        onNodeWithTag(CatalogPickerTestIds.project("b")).performClick()
        waitForIdle()
        caption("~/beta")
        onNodeWithTag("launcher_project_label", useUnmergedTree = true).assertTextEquals("Beta")
        assertEquals("/home/u/beta", saved?.projectLocations?.get(projectLocationKey(HOST, "b")))
    }

    @Test fun several_locations_ask_and_the_pick_is_remembered() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        val catalog = listOf(project("p", "App", "/home/u/app", "/home/u/app-web"))
        pointer { Harness(catalog = flowOf(catalog), onPrefsChange = { saved = it }) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("p")).performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/app")).assertIsDisplayed()
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/app-web")).performClick()
        waitForIdle()
        caption("~/app-web")
        assertEquals("/home/u/app-web", saved?.projectLocations?.get(projectLocationKey(HOST, "p")))
    }

    @Test fun a_remembered_location_is_chosen_directly() = runComposeUiTest {
        val catalog = listOf(project("p", "App", "/home/u/app", "/home/u/app-web"))
        val prefs = LauncherPrefs(projectLocations = mapOf(projectLocationKey(HOST, "p") to "/home/u/app-web"))
        pointer { Harness(catalog = flowOf(catalog), prefs = prefs) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("p")).performClick()
        waitForIdle()
        caption("~/app-web")
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/app")).assertDoesNotExist()
    }

    @Test fun a_stale_remembered_location_is_ignored() = runComposeUiTest {
        val catalog = listOf(project("p", "App", "/home/u/app", "/home/u/app-web"))
        val prefs = LauncherPrefs(projectLocations = mapOf(projectLocationKey(HOST, "p") to "/home/u/moved"))
        pointer { Harness(catalog = flowOf(catalog), prefs = prefs) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("p")).performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/app")).assertIsDisplayed()
    }

    @Test fun a_project_without_locations_registers_the_validated_path() = runComposeUiTest {
        var added: Pair<String, String>? = null
        val catalog = listOf(project("e", "Empty"))
        pointer {
            Harness(
                catalog = flowOf(catalog),
                validate = { PathValidation(ok = true, path = "/home/u/new") },
                addLocation = { id, path ->
                    added = id to path
                    ProjectLocationResult.Added(project("e", "Empty", path))
                },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("e")).performClick()
        waitForIdle()
        onNodeWithTag("launcher_project_pending").assertIsDisplayed()
        onNodeWithTag("launcher_project_search").performTextInput("~/new")
        waitForIdle()
        onNodeWithTag("launcher_use_path").performClick()
        waitForIdle()
        assertEquals("e" to "/home/u/new", added)
        caption("~/new")
    }

    @Test fun a_conflicting_location_names_its_owner_and_does_not_move() = runComposeUiTest {
        val catalog = listOf(project("e", "Empty"), project("o", "Other", "/home/u/taken"))
        pointer {
            Harness(
                catalog = flowOf(catalog),
                validate = { PathValidation(ok = true, path = "/home/u/taken") },
                addLocation = { _, _ -> ProjectLocationResult.Conflict("o") },
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("e")).performClick()
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("~/taken")
        waitForIdle()
        onNodeWithTag("launcher_use_path").performClick()
        waitForIdle()
        onNodeWithTag("launcher_project_error").assertTextEquals("Already in Other")
        caption("~")
    }

    @Test fun sidebar_preselect_applies_the_project() = runComposeUiTest {
        val catalog = listOf(project("a", "Alpha", "/home/u/alpha"), project("b", "Beta", "/home/u/beta"))
        pointer { Harness(catalog = flowOf(catalog), initialProjectId = "b") }
        waitForIdle()
        caption("~/beta")
    }

    @Test fun a_workspace_tab_never_consults_projects() = runComposeUiTest {
        var collected = false
        val catalog = flow {
            collected = true
            emit(listOf(project("a", "Alpha", "/home/u/alpha")))
        }
        pointer { Harness(catalog = catalog, workspaceWorkdir = "/home/u/ws", initialProjectId = "a") }
        waitForIdle()
        assertFalse(collected)
        onNodeWithTag("launcher_project_field").assertDoesNotExist()
        caption("~/ws")
    }
}
