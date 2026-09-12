package dev.supermux.ui.intro

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.DeviceDto
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-run [SetupWizardScreen] — the port of the Vue `SetupView` flow.
 *
 * Covers the stepper contract (labels in the header, Back hidden on step 0, Start→Next), the
 * Agents gate, the footer-less Done step and its finish→create handoff including the inline
 * failure. The phone step has its own suite ([SetupPhoneStepTest]); here it is only walked
 * through.
 */
@OptIn(ExperimentalTestApi::class)
class SetupWizardScreenTest {

    private fun agents(statuses: List<AgentInstallStatus>) =
        AgentSettingsActions(agentStatuses = { statuses })

    private fun forges() = GitHostingActions(forgesLoad = { ForgeConnectionsResponse() })

    private fun devices(
        mints: AtomicInteger = AtomicInteger(0),
        revoked: MutableList<String> = CopyOnWriteArrayList(),
    ) = DevicesSettingsActions(
        devicesLoad = { listOf(DeviceDto(name = "phone", last_seen_at = null)) },
        deviceAdd = { name -> AddDeviceResponse(url = "https://broker.test/pair/x", name = name).also { mints.incrementAndGet() } },
        deviceRevoke = { name -> revoked.add(name); true },
    )

    private val authedAgent = listOf(AgentInstallStatus(kind = "claude", installed = true, authed = true))
    private val unauthedAgent = listOf(AgentInstallStatus(kind = "claude", installed = true, authed = false))

    private fun ComposeUiTest.wizard(
        statuses: List<AgentInstallStatus> = authedAgent,
        onFinish: suspend () -> Boolean = { true },
        onCreateFirstSession: () -> Unit = {},
        devicesActions: DevicesSettingsActions = devices(),
        content: @Composable () -> Unit = {
            SetupWizardScreen(
                agents = agents(statuses),
                forges = forges(),
                devices = devicesActions,
                onFinish = onFinish,
                onCreateFirstSession = onCreateFirstSession,
            )
        },
    ) = setPlatformContent(
        platform = FakePlatform(),
        pointer = true,
        widthClass = WindowWidthClass.Expanded,
    ) {
        SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
    }

    private fun ComposeUiTest.eventually(timeoutMillis: Long = 5_000, block: () -> Unit) =
        waitUntil(timeoutMillis = timeoutMillis) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    /** Click Next/Start until [step] is on screen (the header is the step's own witness). */
    private fun ComposeUiTest.advanceTo(step: Int) {
        repeat(step) {
            eventually { onNodeWithTag("setup_next").assertIsEnabled() }
            onNodeWithTag("setup_next").performClick()
            waitForIdle()
        }
    }

    @Test fun welcome_starts_the_flow_with_no_back() = runComposeUiTest {
        wizard()
        waitForIdle()
        onNodeWithTag("setup_wizard").assertIsDisplayed()
        onNodeWithText("Step 1 of 5 — Welcome").assertIsDisplayed()
        onNodeWithText("Welcome to supermux").assertIsDisplayed()
        onNodeWithText("Start").assertIsDisplayed()
        onNodeWithTag("setup_back").assertDoesNotExist()
    }

    @Test fun back_returns_from_agents_to_welcome() = runComposeUiTest {
        wizard()
        advanceTo(1)
        eventually { onNodeWithText("Step 2 of 5 — Agents").assertIsDisplayed() }
        onNodeWithTag("setup_back").performClick()
        eventually { onNodeWithText("Step 1 of 5 — Welcome").assertIsDisplayed() }
        onNodeWithTag("setup_back").assertDoesNotExist()
    }

    @Test fun agents_step_blocks_next_until_one_agent_is_authed() = runComposeUiTest {
        wizard(statuses = unauthedAgent)
        advanceTo(1)
        eventually { onNodeWithText("Step 2 of 5 — Agents").assertIsDisplayed() }
        eventually { onNodeWithTag("setup_next").assertIsNotEnabled() }
    }

    @Test fun agents_step_unblocks_next_when_an_agent_is_authed() = runComposeUiTest {
        wizard(statuses = authedAgent)
        advanceTo(1)
        eventually { onNodeWithText("Step 2 of 5 — Agents").assertIsDisplayed() }
        eventually { onNodeWithTag("setup_next").assertIsEnabled() }
    }

    @Test fun git_hosting_is_step_three_and_skippable() = runComposeUiTest {
        wizard()
        advanceTo(2)
        eventually { onNodeWithText("Step 3 of 5 — Git Hosting").assertIsDisplayed() }
        onNodeWithTag("setup_next").assertIsEnabled()
    }

    @Test fun phone_step_is_step_four_and_skippable() = runComposeUiTest {
        wizard()
        advanceTo(3)
        eventually { onNodeWithText("Step 4 of 5 — Connect Your Phone").assertIsDisplayed() }
        onNodeWithTag("setup_next").assertIsEnabled()
    }

    @Test fun done_drops_the_footer_and_finishes_before_creating_a_session() = runComposeUiTest {
        val order = CopyOnWriteArrayList<String>()
        wizard(
            onFinish = { order.add("finish"); true },
            onCreateFirstSession = { order.add("create") },
        )
        advanceTo(4)
        eventually { onNodeWithText("Step 5 of 5 — Done").assertIsDisplayed() }
        onNodeWithTag("setup_next").assertDoesNotExist()
        onNodeWithTag("setup_back").assertDoesNotExist()
        onNodeWithText("Create your first session").assertIsDisplayed()
        onNodeWithTag("setup_done").performClick()
        eventually { assertTrue(order.size == 2) }
        assertEquals(listOf("finish", "create"), order.toList())
    }

    @Test fun a_failed_finish_stays_on_done_with_an_inline_error() = runComposeUiTest {
        val created = AtomicInteger(0)
        wizard(onFinish = { false }, onCreateFirstSession = { created.incrementAndGet() })
        advanceTo(4)
        eventually { onNodeWithTag("setup_done").assertIsDisplayed() }
        onNodeWithTag("setup_done").performClick()
        eventually { onNodeWithTag("setup_done_error").assertIsDisplayed() }
        onNodeWithText("Step 5 of 5 — Done").assertIsDisplayed()
        assertEquals(0, created.get())
    }
}
