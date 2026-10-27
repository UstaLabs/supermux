package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.PathValidation
import dev.supermux.proto.ProjectDto
import dev.supermux.session.formatWorkdir
import dev.supermux.proto.ProjectLocationDto
import dev.supermux.state.LauncherDraft
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * The launcher's first run: the first-project screen on a host with no projects, the
 * "Install an agent" panel when a chosen project has no agent to run it, and the broker's own
 * refusal (not "check the connection") wherever it gave one.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherFirstRunTest {

    /** How BrokerApi raises a non-2xx answer (`decode`): SKIE-safe cancellation, body appended. */
    private fun http(status: Int, body: String) =
        CancellationException("BrokerApi request unavailable: HTTP $status $body")

    private val notInstalledBody =
        """{"error":"Claude Code is not installed on this computer.","code":"agent_not_installed","agent":"claude"}"""

    // ── pure: reading a refusal ─────────────────────────────────────────────────────────────────

    @Test fun a_broker_refusal_carries_its_error_code_and_agent() {
        val r = brokerRefusal(http(409, notInstalledBody))!!
        assertEquals("Claude Code is not installed on this computer.", r.message)
        assertEquals(409, r.status)
        assertEquals(AGENT_NOT_INSTALLED, r.code)
        assertEquals("claude", r.agent)
    }

    @Test fun a_refusal_is_found_through_the_spawn_remap() {
        // HostStore.createSessionWithFirstMessageOrThrow → remapSpawnFailure wraps it.
        val wrapped = IllegalStateException("Claude Code is not installed on this computer.", http(409, notInstalledBody))
        assertEquals("claude", agentNotInstalled(wrapped))
        assertNull(agentNotInstalled(IllegalStateException("Broker refused", http(400, """{"error":"bad workdir"}"""))))
        assertNull(agentNotInstalled(IOException("connection reset")))
    }

    @Test fun a_truncated_body_still_yields_the_message() {
        val r = brokerRefusal(http(409, """{"error":"A folder named x already exists","code":"exi"""))!!
        assertEquals("A folder named x already exists", r.message)
    }

    @Test fun failure_wording_prefers_the_broker_and_falls_back_only_for_the_network() {
        assertEquals("A folder named x already exists", hostOpFailureMessage("create x", BrokerRefusal("A folder named x already exists")))
        assertEquals("Couldn't create x (HTTP 500).", hostOpFailureMessage("create x", BrokerRefusal("", status = 500)))
        assertEquals("Couldn't create x — check the connection and try again.", hostOpFailureMessage("create x", IOException("reset")))
        assertEquals("Couldn't create x — check the connection and try again.", hostOpFailureMessage("create x", null))
    }

    @Test fun refusalOrNull_throws_the_refusal_and_nulls_a_network_failure() = runBlocking {
        val thrown = assertFailsWith<BrokerRefusal> { refusalOrNull<String> { throw http(409, """{"error":"exists"}""") } }
        assertEquals("exists", thrown.message)
        assertNull(refusalOrNull<String> { throw IOException("reset") })
        assertEquals("/p", refusalOrNull { "/p" })
    }

    // ── UI: harness ─────────────────────────────────────────────────────────────────────────────

    private fun ComposeUiTest.pointer(content: @Composable () -> Unit) = setPlatformContent(
        platform = FakePlatform(),
        pointer = true,
        widthClass = WindowWidthClass.Expanded,
        inputMode = InputMode.Pointer,
    ) { SupermuxTheme(appearance = AppearanceMode.DARK) { content() } }

    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    private fun agents(vararg installed: String) = AgentSettingsActions(
        agentStatuses = {
            listOf("claude", "codex").map { AgentInstallStatus(kind = it, installed = it in installed, authed = it in installed) }
        },
        agentPollInstall = { null as AgentInstallJob? },
    )

    @Composable
    private fun Launcher(
        projects: (suspend () -> List<String>?)? = { emptyList() },
        catalog: Flow<List<ProjectDto>> = flowOf(emptyList()),
        draft: LauncherDraft = LauncherDraft(),
        validate: (String) -> PathValidation? = { null },
        createLocalRepo: suspend (String) -> String? = { null },
        agentSetup: AgentSettingsActions? = null,
        thisComputer: Boolean = false,
        onSubmit: suspend (String, String) -> String? = { _, _ -> null },
    ) = SessionLauncherScreen(
        sessions = emptyList(),
        home = "/home/u",
        onBack = {},
        actions = LauncherActions(
            listProjectsOrNull = projects,
            projectCatalog = catalog,
            validatePath = { validate(it) },
            createLocalRepo = createLocalRepo,
            agentSetup = agentSetup,
            isThisComputer = { thisComputer },
        ),
        loadDraft = { draft },
        onSubmit = { w, a, _, _, _, _, _, _, _, _, _ -> onSubmit(w, a) },
    )

    // ── first-project screen ────────────────────────────────────────────────────────────────────

    @Test fun a_host_with_no_projects_shows_the_first_project_screen() = runComposeUiTest {
        pointer { Launcher() }
        waitFor { onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).assertIsDisplayed()
        onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).assertIsDisplayed()
        // No search over nothing, no composer.
        onNodeWithTag("launcher_project_field").assertDoesNotExist()
        onNodeWithTag("launcher_message").assertDoesNotExist()
    }

    @Test fun a_host_with_projects_keeps_the_picker() = runComposeUiTest {
        pointer { Launcher(projects = { listOf("/home/u/alpha") }) }
        waitFor { onNodeWithTag("launcher_project_field").assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertDoesNotExist()
    }

    @Test fun a_catalog_project_keeps_the_picker() = runComposeUiTest {
        val catalog = listOf(ProjectDto(id = "a", name = "Alpha", locations = listOf(ProjectLocationDto("l", "/home/u/alpha"))))
        pointer { Launcher(catalog = flowOf(catalog)) }
        waitFor { onNodeWithTag("launcher_project_field").assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertDoesNotExist()
    }

    @Test fun a_failed_project_list_never_assumes_a_fresh_host() = runComposeUiTest {
        pointer { Launcher(projects = { null }) }
        waitFor { onNodeWithTag("launcher_project_field").assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertDoesNotExist()
    }

    @Test fun create_a_new_project_lands_it_as_the_project() = runComposeUiTest {
        var asked: String? = null
        pointer { Launcher(createLocalRepo = { asked = it; "/home/u/projects/$it" }) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).performClick()
        onNodeWithTag(LauncherFirstRunTags.NAME_FIELD).performTextInput("my-app")
        onNodeWithTag(LauncherFirstRunTags.CONFIRM).performClick()
        waitFor { onNodeWithTag("launcher_workdir_caption").assertTextEquals(formatWorkdir("/home/u/projects/my-app", "/home/u")) }
        assertEquals("my-app", asked)
        onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertDoesNotExist()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    @Test fun create_shows_the_brokers_reason() = runComposeUiTest {
        pointer { Launcher(createLocalRepo = { throw BrokerRefusal("A folder named my-app already exists", status = 409) }) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.CREATE_PROJECT).performClick()
        onNodeWithTag(LauncherFirstRunTags.NAME_FIELD).performTextInput("my-app")
        onNodeWithTag(LauncherFirstRunTags.CONFIRM).performClick()
        waitFor { onNodeWithTag(LauncherFirstRunTags.ERROR).assertTextEquals("A folder named my-app already exists") }
        onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertIsDisplayed()
    }

    @Test fun open_a_folder_validates_it_through_the_broker() = runComposeUiTest {
        pointer {
            Launcher(validate = { p ->
                if (p == "~/code/app") PathValidation(ok = true, path = "/home/u/code/app")
                else PathValidation(ok = false, error = "No such folder")
            })
        }
        waitFor { onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).performClick()
        onNodeWithTag(LauncherFirstRunTags.PATH_FIELD).performTextInput("~/nope")
        onNodeWithTag(LauncherFirstRunTags.CONFIRM).performClick()
        waitFor { onNodeWithTag(LauncherFirstRunTags.ERROR).assertTextEquals("No such folder") }
        onNodeWithTag(LauncherFirstRunTags.PATH_FIELD).performTextClearance()
        onNodeWithTag(LauncherFirstRunTags.PATH_FIELD).performTextInput("~/code/app")
        onNodeWithTag(LauncherFirstRunTags.CONFIRM).performClick()
        waitFor { onNodeWithTag("launcher_workdir_caption").assertTextEquals(formatWorkdir("/home/u/code/app", "/home/u")) }
    }

    // ── install-an-agent panel ──────────────────────────────────────────────────────────────────

    @Test fun no_installed_agent_shows_the_install_panel_instead_of_the_composer() = runComposeUiTest {
        pointer { Launcher(draft = LauncherDraft(workdir = "/home/u/app"), agentSetup = agents()) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT).assertIsDisplayed() }
        // One card per agent, each with its own Install.
        waitFor { onNodeWithTag(LauncherFirstRunTags.agentCard("claude")).assertExists() }
        onNodeWithTag(LauncherFirstRunTags.agentInstall("codex")).assertExists()
        onNodeWithTag("launcher_message").assertDoesNotExist()
    }

    @Test fun the_one_installed_agent_is_just_used() = runComposeUiTest {
        var sentAgent: String? = null
        pointer {
            Launcher(
                draft = LauncherDraft(workdir = "/home/u/app", text = "go"),
                agentSetup = agents("codex"),
                onSubmit = { _, a -> sentAgent = a; null },
            )
        }
        waitFor { onNodeWithTag("launcher_message").assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT).assertDoesNotExist()
        onNodeWithTag("launcher_submit").performClick()
        waitFor { assertEquals("codex", sentAgent) }
    }

    @Test fun no_project_yet_never_shows_the_install_panel() = runComposeUiTest {
        pointer { Launcher(agentSetup = agents()) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.FIRST_PROJECT).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT).assertDoesNotExist()
    }

    @Test fun a_spawn_refused_for_a_missing_agent_opens_the_install_panel() = runComposeUiTest {
        // Statuses said claude was there (stale); the spawn says otherwise.
        var calls = 0
        val setup = AgentSettingsActions(
            agentStatuses = {
                calls++
                listOf(AgentInstallStatus(kind = "claude", installed = calls == 1, authed = calls == 1))
            },
        )
        pointer {
            Launcher(
                draft = LauncherDraft(workdir = "/home/u/app", text = "go"),
                agentSetup = setup,
                onSubmit = { _, _ ->
                    throw IllegalStateException("Claude Code is not installed on this computer.", http(409, notInstalledBody))
                },
            )
        }
        waitFor { onNodeWithTag("launcher_message").assertIsDisplayed() }
        onNodeWithTag("launcher_submit").performClick()
        waitFor { onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT_MESSAGE)
            .assertTextEquals("Claude Code is not installed on this computer.")
        // Said once, by the panel — not again as the generic error line.
        onNodeWithTag("launcher_error").assertDoesNotExist()
    }

    @Test fun without_agent_setup_a_refusal_is_still_the_brokers_message() = runComposeUiTest {
        pointer {
            Launcher(
                draft = LauncherDraft(workdir = "/home/u/app", text = "go"),
                onSubmit = { _, _ ->
                    throw IllegalStateException("Claude Code is not installed on this computer.", http(409, notInstalledBody))
                },
            )
        }
        waitFor { onNodeWithTag("launcher_message").assertIsDisplayed() }
        onNodeWithTag("launcher_submit").performClick()
        waitFor { onNodeWithTag("launcher_error").assertTextEquals("Claude Code is not installed on this computer.") }
    }

    // ── redesign: names, the native picker, installing from a card ─────────────────────────────

    @Test fun a_typed_project_name_becomes_a_folder_name_the_broker_accepts() {
        assertEquals("my-app", projectFolderName("  my app "))
        assertEquals("Todo_v2.1", projectFolderName("Todo_v2.1"))
        assertEquals("app", projectFolderName("--/app!"))
        assertEquals("", projectFolderName("  "))
    }

    @Test fun this_computer_opens_the_native_folder_dialog() = runComposeUiTest {
        var asked = 0
        setPlatformContent(
            platform = object : FakePlatform() {
                override suspend fun pickDirectory(title: String): String? { asked++; return "/home/u/picked" }
            },
            pointer = true,
            widthClass = WindowWidthClass.Expanded,
            inputMode = InputMode.Pointer,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Launcher(thisComputer = true, validate = { PathValidation(ok = true, path = it) })
            }
        }
        waitFor { onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.TYPE_PATH).assertIsDisplayed()
        onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).performClick()
        waitFor { onNodeWithTag("launcher_workdir_caption").assertTextEquals(formatWorkdir("/home/u/picked", "/home/u")) }
        assertEquals(1, asked)
    }

    @Test fun another_host_types_the_path_and_offers_no_dialog() = runComposeUiTest {
        pointer { Launcher(thisComputer = false) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.TYPE_PATH).assertDoesNotExist()
        onNodeWithTag(LauncherFirstRunTags.OPEN_FOLDER).performClick()
        waitFor { onNodeWithTag(LauncherFirstRunTags.PATH_FIELD).assertIsDisplayed() }
    }

    @Test fun installing_from_a_card_brings_the_composer_back_when_done() = runComposeUiTest {
        var installed = false
        var started = false
        var polls = 0
        val setup = AgentSettingsActions(
            agentStatuses = { listOf(AgentInstallStatus(kind = "claude", installed = installed), AgentInstallStatus(kind = "codex")) },
            agentStartInstall = { started = true; AgentInstallJob(state = "running") },
            agentPollInstall = { kind ->
                when {
                    kind != "claude" || !started -> null
                    polls++ < 2 -> AgentInstallJob(state = "running")
                    else -> { installed = true; AgentInstallJob(state = "done") }
                }
            },
        )
        pointer { Launcher(draft = LauncherDraft(workdir = "/home/u/app"), agentSetup = setup) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.agentInstall("claude")).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.agentInstall("claude")).performClick()
        waitFor { onNodeWithTag("launcher_message").assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.INSTALL_AGENT).assertDoesNotExist()
    }

    @Test fun a_failed_install_says_why_on_its_card() = runComposeUiTest {
        val setup = AgentSettingsActions(
            agentStatuses = { listOf(AgentInstallStatus(kind = "claude")) },
            agentStartInstall = { AgentInstallJob(state = "failed", exitCode = 1, error = "no network") },
        )
        pointer { Launcher(draft = LauncherDraft(workdir = "/home/u/app"), agentSetup = setup) }
        waitFor { onNodeWithTag(LauncherFirstRunTags.agentInstall("claude")).assertIsDisplayed() }
        onNodeWithTag(LauncherFirstRunTags.agentInstall("claude")).performClick()
        waitFor { onNodeWithTag(LauncherFirstRunTags.agentError("claude")).assertTextEquals("Installation failed (exit code 1). No network") }
    }
}
