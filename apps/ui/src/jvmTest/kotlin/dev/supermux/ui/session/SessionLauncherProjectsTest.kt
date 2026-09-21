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
import kotlin.test.assertNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        addLocation: suspend (String, String) -> ProjectLocationResult = { _, _ -> ProjectLocationResult.Failed },
        workspaceWorkdir: String? = null,
        initialProjectId: String? = null,
        selectedHost: String = HOST,
        onInitialProjectApplied: () -> Unit = {},
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
                selectedHost = selectedHost,
                initialProjectHost = initialProjectId?.let { HOST },
                initialProjectId = initialProjectId,
                onInitialProjectApplied = onInitialProjectApplied,
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

    @Test fun a_catalog_arriving_after_restore_switches_to_the_project_picker_and_preselects() = runComposeUiTest {
        val catalog = MutableStateFlow<List<ProjectDto>>(emptyList())
        var applied = 0
        pointer { Harness(catalog = catalog, initialProjectId = "b", onInitialProjectApplied = { applied++ }) }
        waitForIdle()
        caption("~")
        assertEquals(0, applied)
        catalog.value = listOf(project("a", "Alpha", "/home/u/alpha"), project("b", "Beta", "/home/u/beta"))
        waitForIdle()
        caption("~/beta")
        assertEquals(1, applied)
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("a")).assertIsDisplayed()
    }

    @Test fun another_host_ignores_this_hosts_remembered_location() = runComposeUiTest {
        val catalog = listOf(project("p", "App", "/home/u/app", "/home/u/app-web"))
        // Remembered on h1; the launcher targets h2, where "p" is a different project.
        val prefs = LauncherPrefs(projectLocations = mapOf(projectLocationKey(HOST, "p") to "/home/u/app-web"))
        pointer { Harness(catalog = flowOf(catalog), prefs = prefs, selectedHost = "h2") }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("p")).performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/app")).assertIsDisplayed()
        caption("~")
    }

    @Test fun changing_agent_after_a_location_pick_keeps_the_remembered_locations() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        val catalog = listOf(project("b", "Beta", "/home/u/beta"))
        pointer { Harness(catalog = flowOf(catalog), onPrefsChange = { saved = it }) }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("b")).performClick()
        waitForIdle()
        onNodeWithTag("launcher_agent_pill").performClick()
        onNodeWithTag("agent_codex").performClick()
        waitForIdle()
        assertEquals("codex", saved?.agent)
        assertEquals("/home/u/beta", saved?.projectLocations?.get(projectLocationKey(HOST, "b")))
    }

    @Test fun a_conflict_offers_the_owning_project_and_uses_exactly_that_path() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        val catalog = listOf(project("e", "Empty"), project("o", "Other", "/home/u/other", "/home/u/taken"))
        pointer {
            Harness(
                catalog = flowOf(catalog),
                onPrefsChange = { saved = it },
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
        onNodeWithTag("launcher_project_use_owner").assertTextEquals("Use Other")
        onNodeWithTag("launcher_project_use_owner").performClick()
        waitForIdle()
        // Other has two locations; the conflicting path IS one of them, so no location list.
        caption("~/taken")
        onNodeWithTag("launcher_project_label", useUnmergedTree = true).assertTextEquals("Other")
        onNodeWithTag("launcher_project_error").assertDoesNotExist()
        onNodeWithTag(CatalogPickerTestIds.location("/home/u/other")).assertDoesNotExist()
        assertEquals("/home/u/taken", saved?.projectLocations?.get(projectLocationKey(HOST, "o")))
    }

    @Test fun a_host_switch_mid_registration_drops_the_result() = runComposeUiTest {
        var saved: LauncherPrefs? = null
        var host by mutableStateOf(HOST)
        val gate = CompletableDeferred<Unit>()
        val catalog = listOf(project("e", "Empty"))
        pointer {
            Harness(
                catalog = flowOf(catalog),
                onPrefsChange = { saved = it },
                validate = { PathValidation(ok = true, path = "/home/u/new") },
                addLocation = { _, path ->
                    gate.await()
                    ProjectLocationResult.Added(project("e", "Empty", path))
                },
                selectedHost = host,
            )
        }
        waitForIdle()
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("e")).performClick()
        waitForIdle()
        onNodeWithTag("launcher_project_search").performTextInput("~/new")
        waitForIdle()
        onNodeWithTag("launcher_use_path").performClick()
        waitForIdle()
        host = "h2"
        waitForIdle()
        onNodeWithTag("launcher_project_pending").assertDoesNotExist()
        gate.complete(Unit)
        waitForIdle()
        caption("~")
        assertNull(saved?.projectLocations?.get(projectLocationKey(HOST, "e")))
        assertNull(saved?.projectLocations?.get(projectLocationKey("h2", "e")))
    }

    @Test fun preselect_is_one_shot_per_request_and_a_new_request_applies_again() = runComposeUiTest {
        var request by mutableStateOf<String?>("b")
        var applied = 0
        val catalog = listOf(project("a", "Alpha", "/home/u/alpha"), project("b", "Beta", "/home/u/beta"))
        pointer {
            Harness(
                catalog = flowOf(catalog),
                initialProjectId = request,
                // The shell consumes the route's request once applied (ShellUiState.consumeLauncherProject).
                onInitialProjectApplied = { applied++; request = null },
            )
        }
        waitForIdle()
        caption("~/beta")
        assertEquals(1, applied)
        onNodeWithTag("launcher_project_field").performClick()
        waitForIdle()
        onNodeWithTag(CatalogPickerTestIds.project("a")).performClick()
        waitForIdle()
        caption("~/alpha")
        // A second sidebar "+" on the same project is a new request: it applies again.
        request = "b"
        waitForIdle()
        caption("~/beta")
        assertEquals(2, applied)
    }
}
