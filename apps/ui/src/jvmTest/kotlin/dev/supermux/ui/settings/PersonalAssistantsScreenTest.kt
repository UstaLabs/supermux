package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.PADto
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The shared [PersonalAssistantsScreen] (cluster E4) — a NEW suite: neither host's copy carried a
 * single test tag or test, so this is the first coverage the screen has ever had. It pins the list,
 * the create form, the kill confirm and the Compact chrome Android contributed.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PersonalAssistantsScreenTest {

    private fun ComposeUiTest.paContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    private fun samplePas() = listOf(
        PADto(id = "pa1", name = "Ada", agent = "claude", model = "opus", connected = true, isDefault = true),
        PADto(id = "pa2", name = "Bob", workdir = "/home/me/work", connected = false),
    )

    private fun screen(
        load: suspend () -> List<PADto> = { samplePas() },
        create: suspend (String, String, String?) -> Boolean = { _, _, _ -> true },
        kill: suspend (String) -> Unit = {},
        topBarShown: Boolean = true,
    ) = @Composable {
        PersonalAssistantsScreen(
            actions = PersonalAssistantsActions(load, create, kill),
            topBarShown = topBarShown,
        )
    }

    private fun ComposeUiTest.awaitTag(tag: String) = waitUntil(timeoutMillis = 5_000) {
        try {
            onNodeWithTag(tag).assertIsDisplayed()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Test fun assistants_render_with_agent_model_and_default_badge() = runComposeUiTest {
        paContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        awaitTag("pa_row_pa1")
        onNodeWithTag("pa_settings_screen").assertIsDisplayed()
        onNodeWithTag("pa_row_pa2").assertIsDisplayed()
        // The badge sits inside the ListItem's merged headline, so ask the unmerged tree.
        onNodeWithTag("pa_default_pa1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("pa_default_pa2", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("claude · opus").assertIsDisplayed()
        // No agent/model → the workdir stands in.
        onNodeWithText("/home/me/work").assertIsDisplayed()
    }

    @Test fun empty_list_shows_the_optional_copy() = runComposeUiTest {
        paContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen(load = { emptyList() })() }
        }
        waitForIdle()
        awaitTag("pa_settings_empty")
        onNodeWithText("No personal assistants").assertIsDisplayed()
        onNodeWithTag("pa_list").assertDoesNotExist()
    }

    @Test fun create_needs_a_name_then_posts_and_reloads() = runComposeUiTest {
        val created = CopyOnWriteArrayList<Triple<String, String, String?>>()
        val loads = AtomicReference(0)
        paContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    load = {
                        loads.set(loads.get() + 1)
                        samplePas()
                    },
                    create = { name, agent, focus ->
                        created.add(Triple(name, agent, focus))
                        true
                    },
                )()
            }
        }
        waitForIdle()
        awaitTag("pa_create_button")
        onNodeWithTag("pa_create_button").performClick()
        waitForIdle()
        onNodeWithTag("pa_create_dialog").assertIsDisplayed()
        // A blank name cannot be submitted.
        onNodeWithTag("pa_create_confirm").assertIsNotEnabled()
        onNodeWithTag("pa_create_name").performTextInput("  Cleo  ")
        onNodeWithTag("pa_create_agent_codex").performClick()
        onNodeWithTag("pa_create_focus").performTextInput("triage")
        onNodeWithTag("pa_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { created.isNotEmpty() && loads.get() >= 2 }
        assertEquals(Triple("Cleo", "codex", "triage"), created.first())
        onNodeWithTag("pa_create_dialog").assertDoesNotExist()
    }

    /** A rejected create keeps the form open with what was typed. */
    @Test fun failed_create_keeps_the_form_open() = runComposeUiTest {
        paContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(create = { _, _, _ -> false })()
            }
        }
        waitForIdle()
        awaitTag("pa_create_button")
        onNodeWithTag("pa_create_button").performClick()
        waitForIdle()
        onNodeWithTag("pa_create_name").performTextInput("Nope")
        onNodeWithTag("pa_create_confirm").performClick()
        waitForIdle()
        onNodeWithTag("pa_create_dialog").assertIsDisplayed()
        onNodeWithTag("pa_create_cancel").performClick()
        waitForIdle()
        onNodeWithTag("pa_create_dialog").assertDoesNotExist()
    }

    @Test fun kill_requires_confirm_then_reloads() = runComposeUiTest {
        val killed = CopyOnWriteArrayList<String>()
        val remaining = AtomicReference(samplePas())
        paContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    load = { remaining.get() },
                    kill = { id ->
                        killed.add(id)
                        remaining.set(remaining.get().filterNot { it.id == id })
                    },
                )()
            }
        }
        waitForIdle()
        awaitTag("pa_row_pa1")
        onNodeWithTag("pa_kill_pa1").performClick()
        waitForIdle()
        onNodeWithTag("pa_kill_dialog").assertIsDisplayed()
        onNodeWithText("Kill Ada?").assertIsDisplayed()
        onNodeWithTag("pa_kill_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("pa_row_pa1").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(listOf("pa1"), killed.toList())
        onNodeWithTag("pa_row_pa2").assertIsDisplayed()
    }

    @Test fun kill_cancel_keeps_the_row() = runComposeUiTest {
        val killed = CopyOnWriteArrayList<String>()
        paContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(kill = { killed.add(it) })()
            }
        }
        waitForIdle()
        awaitTag("pa_row_pa1")
        onNodeWithTag("pa_kill_pa1").performClick()
        waitForIdle()
        onNodeWithTag("pa_kill_cancel").performClick()
        waitForIdle()
        onNodeWithTag("pa_kill_dialog").assertDoesNotExist()
        onNodeWithTag("pa_row_pa1").assertIsDisplayed()
        assertTrue(killed.isEmpty())
    }

    // ── Compact / touch (Android's branch) ─────────────────────────────────────────────────────

    @Test fun compact_page_paints_its_own_top_bar_and_fab() = runComposeUiTest {
        var backs = 0
        paContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                PersonalAssistantsScreen(
                    actions = PersonalAssistantsActions(load = { samplePas() }),
                    onBack = { backs++ },
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        awaitTag("pa_row_pa1")
        onNodeWithTag("pa_create_button").assertDoesNotExist()
        onNodeWithTag("pa_create_fab").performClick()
        waitForIdle()
        onNodeWithTag("pa_create_dialog").assertIsDisplayed()
        onNodeWithTag("pa_create_cancel").performClick()
        waitForIdle()
        onNodeWithTag("pa_settings_back").performClick()
        assertEquals(1, backs)
    }

    /** With the hub's chrome already up, the page adds no second top bar and keeps the button. */
    @Test fun compact_page_defers_to_the_hub_chrome() = runComposeUiTest {
        paContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
        }
        waitForIdle()
        awaitTag("pa_row_pa1")
        onNodeWithTag("pa_settings_back").assertDoesNotExist()
        onNodeWithTag("pa_create_fab").assertDoesNotExist()
        onNodeWithTag("pa_create_button").assertIsDisplayed()
    }
}
