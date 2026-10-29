package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ModelInfo
import dev.supermux.net.PADto
import dev.supermux.state.AccountResult
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(ExperimentalTestApi::class)
class AgentsScreenTest {

    private fun ComposeUiTest.agentsContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
    }

    private fun sample() = listOf(
        PADto(id = "a1", name = "Ada", agent = "claude", model = "opus", connected = true, isDefault = true),
        PADto(id = "a2", name = "Bob", workdir = "/home/me/work", connected = false),
    )

    private fun actions(
        load: suspend () -> AccountResult<List<PADto>> = { AccountResult.Ok(sample()) },
        providers: suspend () -> List<String> = { listOf("claude", "codex") },
        models: suspend (String) -> List<ModelInfo> = { p -> if (p == "codex") listOf(ModelInfo("gpt-6", "GPT-6")) else emptyList() },
        create: suspend (String, String, String?) -> AccountResult<Unit> = { _, _, _ -> AccountResult.Ok(Unit) },
        kill: suspend (String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
        setDefault: suspend (String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
        rename: suspend (String, String) -> AccountResult<Unit> = { _, _ -> AccountResult.Ok(Unit) },
    ) = AgentsActions(load, providers, models, create, kill, setDefault, rename)

    private fun ComposeUiTest.awaitTag(tag: String) = waitUntil(timeoutMillis = 5_000) {
        try { onNodeWithTag(tag).assertIsDisplayed(); true } catch (_: Throwable) { false }
    }

    private fun ComposeUiTest.openCreate() {
        awaitTag("agents_create_button")
        onNodeWithTag("agents_create_button").performClick()
        awaitTag("agents_create_dialog")
    }

    @Test fun agents_render_with_provider_model_and_default_badge() = runComposeUiTest {
        agentsContent { AgentsScreen(actions = actions(), topBarShown = true) }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_row_a2").assertIsDisplayed()
        onNodeWithTag("agents_default_a1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("agents_default_a2", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("claude · opus").assertIsDisplayed()
        onNodeWithText("/home/me/work").assertIsDisplayed()
    }

    @Test fun an_empty_list_shows_the_empty_state() = runComposeUiTest {
        agentsContent { AgentsScreen(actions = actions(load = { AccountResult.Ok(emptyList()) }), topBarShown = true) }
        awaitTag("agents_empty")
        onNodeWithText("No agents yet").assertIsDisplayed()
    }

    @Test fun a_load_failure_is_an_error_with_retry_not_the_empty_state() = runComposeUiTest {
        val calls = AtomicInteger(0)
        agentsContent {
            AgentsScreen(
                actions = actions(load = {
                    if (calls.incrementAndGet() == 1) AccountResult.Failed(500, null, "Broker is restarting") else AccountResult.Ok(sample())
                }),
                topBarShown = true,
            )
        }
        awaitTag("agents_load_error")
        onNodeWithText("Broker is restarting").assertIsDisplayed()
        onNodeWithTag("agents_empty").assertDoesNotExist()
        onNodeWithTag("agents_retry").performClick()
        awaitTag("agents_row_a1")
    }

    @Test fun the_provider_list_comes_from_the_host_not_a_fixed_list() = runComposeUiTest {
        agentsContent { AgentsScreen(actions = actions(providers = { listOf("codex") }), topBarShown = true) }
        openCreate()
        onNodeWithTag("agents_create_provider_codex").assertIsDisplayed()
        onNodeWithTag("agents_create_provider_claude").assertDoesNotExist()
    }

    @Test fun no_installed_provider_disables_create() = runComposeUiTest {
        agentsContent { AgentsScreen(actions = actions(providers = { emptyList() }), topBarShown = true) }
        openCreate()
        awaitTag("agents_create_no_provider")
        onNodeWithTag("agents_create_name").performTextInput("Cleo")
        onNodeWithTag("agents_create_confirm").assertIsNotEnabled()
    }

    @Test fun an_invalid_name_is_explained_and_blocks_create() = runComposeUiTest {
        agentsContent { AgentsScreen(actions = actions(), topBarShown = true) }
        openCreate()
        onNodeWithTag("agents_create_confirm").assertIsNotEnabled()
        onNodeWithTag("agents_create_name").performTextInput("a/b")
        onNodeWithText("A name can't contain / or \\").assertIsDisplayed()
        onNodeWithTag("agents_create_confirm").assertIsNotEnabled()
    }

    @Test fun create_sends_name_provider_and_model_then_reloads() = runComposeUiTest {
        val created = CopyOnWriteArrayList<Triple<String, String, String?>>()
        val loads = AtomicInteger(0)
        agentsContent {
            AgentsScreen(
                actions = actions(
                    load = { loads.incrementAndGet(); AccountResult.Ok(sample()) },
                    create = { n, p, m -> created.add(Triple(n, p, m)); AccountResult.Ok(Unit) },
                ),
                topBarShown = true,
            )
        }
        openCreate()
        onNodeWithTag("agents_create_name").performTextInput("  Cleo  ")
        onNodeWithTag("agents_create_provider_codex").performClick()
        awaitTag("agents_create_model_gpt-6")
        onNodeWithTag("agents_create_model_gpt-6").performClick()
        onNodeWithTag("agents_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { created.isNotEmpty() && loads.get() >= 2 }
        assertEquals(Triple("Cleo", "codex", "gpt-6"), created.single())
        onNodeWithTag("agents_create_dialog").assertDoesNotExist()
    }

    @Test fun the_default_model_is_sent_as_null() = runComposeUiTest {
        val created = AtomicReference<Triple<String, String, String?>?>(null)
        agentsContent {
            AgentsScreen(actions = actions(create = { n, p, m -> created.set(Triple(n, p, m)); AccountResult.Ok(Unit) }), topBarShown = true)
        }
        openCreate()
        onNodeWithTag("agents_create_name").performTextInput("Dee")
        onNodeWithTag("agents_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { created.get() != null }
        assertEquals(Triple("Dee", "claude", null), created.get())
    }

    @Test fun confirm_is_disabled_while_the_create_is_in_flight() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        agentsContent {
            AgentsScreen(
                actions = actions(create = { _, _, _ -> calls.incrementAndGet(); gate.await(); AccountResult.Ok(Unit) }),
                topBarShown = true,
            )
        }
        openCreate()
        onNodeWithTag("agents_create_name").performTextInput("Eve")
        onNodeWithTag("agents_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { calls.get() == 1 }
        onNodeWithTag("agents_create_confirm").assertIsNotEnabled()
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) {
            try { onNodeWithTag("agents_create_dialog").assertDoesNotExist(); true } catch (_: Throwable) { false }
        }
        assertEquals(1, calls.get())
    }

    @Test fun a_refused_create_shows_the_brokers_message_and_keeps_the_form() = runComposeUiTest {
        agentsContent {
            AgentsScreen(
                actions = actions(create = { _, _, _ -> AccountResult.Failed(409, "name_taken", "An agent named Ada already exists") }),
                topBarShown = true,
            )
        }
        openCreate()
        onNodeWithTag("agents_create_name").performTextInput("Ada")
        onNodeWithTag("agents_create_confirm").performClick()
        awaitTag("agents_create_error")
        onNodeWithText("An agent named Ada already exists").assertIsDisplayed()
        onNodeWithTag("agents_create_dialog").assertIsDisplayed()
        onNodeWithTag("agents_create_confirm").assertIsEnabled()
    }

    @Test fun kill_requires_confirm_then_reloads() = runComposeUiTest {
        val killed = CopyOnWriteArrayList<String>()
        val remaining = AtomicReference(sample())
        agentsContent {
            AgentsScreen(
                actions = actions(
                    load = { AccountResult.Ok(remaining.get()) },
                    kill = { id -> killed.add(id); remaining.set(remaining.get().filterNot { it.id == id }); AccountResult.Ok(Unit) },
                ),
                topBarShown = true,
            )
        }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_kill_a1").performClick()
        onNodeWithText("Kill Ada?").assertIsDisplayed()
        onNodeWithTag("agents_kill_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try { onNodeWithTag("agents_row_a1").assertDoesNotExist(); true } catch (_: Throwable) { false }
        }
        assertEquals(listOf("a1"), killed.toList())
    }

    @Test fun a_failed_kill_is_reported_and_the_row_stays() = runComposeUiTest {
        agentsContent {
            AgentsScreen(actions = actions(kill = { AccountResult.Failed(404, null, null) }), topBarShown = true)
        }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_kill_a1").performClick()
        onNodeWithTag("agents_kill_confirm").performClick()
        awaitTag("agents_kill_error")
        onNodeWithText("Couldn't kill Ada — check the connection and try again.").assertIsDisplayed()
        onNodeWithTag("agents_row_a1").assertIsDisplayed()
    }

    @Test fun kill_cancel_keeps_the_row() = runComposeUiTest {
        val killed = CopyOnWriteArrayList<String>()
        agentsContent { AgentsScreen(actions = actions(kill = { killed.add(it); AccountResult.Ok(Unit) }), topBarShown = true) }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_kill_a1").performClick()
        onNodeWithTag("agents_kill_cancel").performClick()
        onNodeWithTag("agents_kill_dialog").assertDoesNotExist()
        assertTrue(killed.isEmpty())
    }

    @Test fun compact_page_paints_its_own_top_bar_and_fab() = runComposeUiTest {
        var backs = 0
        agentsContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            AgentsScreen(actions = actions(), onBack = { backs++ }, topBarShown = false)
        }
        awaitTag("agents_row_a1")
        onNodeWithText("Agents").assertIsDisplayed()
        onNodeWithTag("agents_create_button").assertDoesNotExist()
        onNodeWithTag("agents_create_fab").performClick()
        awaitTag("agents_create_dialog")
        onNodeWithTag("agents_create_cancel").performClick()
        onNodeWithTag("agents_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun compact_page_defers_to_the_hub_chrome() = runComposeUiTest {
        agentsContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            AgentsScreen(actions = actions(), topBarShown = true)
        }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_back").assertDoesNotExist()
        onNodeWithTag("agents_create_fab").assertDoesNotExist()
        onNodeWithTag("agents_create_button").assertIsDisplayed()
    }

    @Test fun agents_changed_reloads_the_list() = runComposeUiTest {
        val rows = AtomicReference(sample())
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        agentsContent {
            AgentsScreen(actions = AgentsActions(load = { AccountResult.Ok(rows.get()) }, changes = changes), topBarShown = true)
        }
        awaitTag("agents_row_a1")
        rows.set(sample() + PADto(id = "a3", name = "Cleo"))
        waitUntil(timeoutMillis = 5_000) {
            changes.tryEmit(Unit)
            try { onNodeWithTag("agents_row_a3").assertIsDisplayed(); true } catch (_: Throwable) { false }
        }
        onNodeWithTag("agents_loading").assertDoesNotExist()
    }

    @Test fun make_default_is_offered_only_on_non_default_rows_and_reloads() = runComposeUiTest {
        val made = CopyOnWriteArrayList<String>()
        val rows = AtomicReference(sample())
        agentsContent {
            AgentsScreen(
                actions = actions(
                    load = { AccountResult.Ok(rows.get()) },
                    setDefault = { id -> made.add(id); rows.set(rows.get().map { it.copy(isDefault = it.id == id) }); AccountResult.Ok(Unit) },
                ),
                topBarShown = true,
            )
        }
        awaitTag("agents_row_a2")
        onNodeWithTag("agents_make_default_a1").assertDoesNotExist()
        onNodeWithTag("agents_make_default_a2").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try { onNodeWithTag("agents_default_a2", useUnmergedTree = true).assertIsDisplayed(); true } catch (_: Throwable) { false }
        }
        assertEquals(listOf("a2"), made.toList())
        onNodeWithTag("agents_default_a1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun rename_validates_sends_trimmed_name_and_reloads() = runComposeUiTest {
        val renamed = CopyOnWriteArrayList<Pair<String, String>>()
        val rows = AtomicReference(sample())
        agentsContent {
            AgentsScreen(
                actions = actions(
                    load = { AccountResult.Ok(rows.get()) },
                    rename = { id, n -> renamed.add(id to n); rows.set(rows.get().map { if (it.id == id) it.copy(name = n) else it }); AccountResult.Ok(Unit) },
                ),
                topBarShown = true,
            )
        }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_rename_a1").performClick()
        awaitTag("agents_rename_dialog")
        onNodeWithTag("agents_rename_name").performTextClearance()
        onNodeWithTag("agents_rename_name").performTextInput(".hidden")
        onNodeWithText("A name can't start with a dot").assertIsDisplayed()
        onNodeWithTag("agents_rename_confirm").assertIsNotEnabled()
        onNodeWithTag("agents_rename_name").performTextClearance()
        onNodeWithTag("agents_rename_name").performTextInput("  Kahya ")
        onNodeWithTag("agents_rename_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try { onNodeWithText("Kahya").assertIsDisplayed(); true } catch (_: Throwable) { false }
        }
        assertEquals(listOf("a1" to "Kahya"), renamed.toList())
    }

    @Test fun a_refused_rename_keeps_the_dialog_open_with_the_brokers_message() = runComposeUiTest {
        agentsContent {
            AgentsScreen(
                actions = actions(rename = { _, _ -> AccountResult.Failed(409, "name_taken", "name already in use: Bob") }),
                topBarShown = true,
            )
        }
        awaitTag("agents_row_a1")
        onNodeWithTag("agents_rename_a1").performClick()
        onNodeWithTag("agents_rename_name").performTextClearance()
        onNodeWithTag("agents_rename_name").performTextInput("Bob")
        onNodeWithTag("agents_rename_confirm").performClick()
        awaitTag("agents_rename_error")
        onNodeWithText("name already in use: Bob").assertIsDisplayed()
        onNodeWithTag("agents_rename_dialog").assertIsDisplayed()
    }
}
