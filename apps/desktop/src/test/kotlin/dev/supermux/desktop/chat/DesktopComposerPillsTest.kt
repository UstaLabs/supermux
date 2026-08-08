package dev.supermux.desktop.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.DEFAULT_MODEL_ID
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The in-composer model + reasoning pills (M-uxfix): the pure current→label / DEFAULT_MODEL_ID
 * mapping, and the DropdownMenu-driven pill UX via [runComposeUiTest] — the model pill shows the
 * current label, opens its menu, and a pick fires the callback + updates the shown current; the
 * reasoning pill is gated on `visible && levels > 1`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopComposerPillsTest {

    private val models = ModelsResponse(
        agent = "claude",
        current = "opus",
        models = listOf(ModelInfo("opus", "Opus"), ModelInfo("sonnet", "Sonnet")),
    )

    // ── pure current→label / selected-id mapping ────────────────────────────────
    @Test fun model_label_uses_display_name() {
        assertEquals("Opus", composerModelLabel("opus", models.models))
    }

    @Test fun model_label_null_or_blank_is_default() {
        assertEquals("Default", composerModelLabel(null, models.models))
        assertEquals("Default", composerModelLabel("", models.models))
        assertEquals("Default", composerModelLabel("   ", models.models))
    }

    @Test fun model_label_unknown_id_falls_back_to_the_id() {
        assertEquals("mystery", composerModelLabel("mystery", models.models))
    }

    @Test fun model_selected_id_maps_null_to_the_default_sentinel() {
        assertEquals(DEFAULT_MODEL_ID, composerModelSelectedId(null))
        assertEquals(DEFAULT_MODEL_ID, composerModelSelectedId(""))
        assertEquals("opus", composerModelSelectedId("opus"))
    }

    // ── model pill UX ───────────────────────────────────────────────────────────
    @Test fun model_pill_shows_current_opens_menu_and_a_pick_fires_and_updates() = runComposeUiTest {
        var picked: String? = null
        setContent {
            var data by remember { mutableStateOf<ModelsResponse?>(models) }
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = data,
                onPickModel = { picked = it; data = data?.copy(current = it.ifBlank { null }) },
            )
        }
        // Current label shown.
        onNodeWithTag("composer-model-pill").assertIsDisplayed()
        onNodeWithText("Opus").assertIsDisplayed()
        // Open the menu and pick Sonnet.
        onNodeWithTag("composer-model-pill").performClick()
        onNodeWithTag("composer-model-sonnet").performClick()
        assertEquals("sonnet", picked)
        // Optimistic current update propagates to the pill label.
        onNodeWithText("Sonnet").assertIsDisplayed()
    }

    @Test fun model_pill_default_row_picks_the_empty_string() = runComposeUiTest {
        var picked: String? = "unset"
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = models,
                onPickModel = { picked = it },
            )
        }
        onNodeWithTag("composer-model-pill").performClick()
        onNodeWithTag("composer-model-$DEFAULT_MODEL_ID").performClick()
        assertEquals("", picked)
    }

    @Test fun model_pill_falls_back_to_session_model_before_catalog_loads() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = null,
                sessionModel = "sonnet",
            )
        }
        // No catalog yet, but session.model gives the pill its label (raw id, not in a catalog).
        onNodeWithTag("composer-model-pill").assertIsDisplayed()
        onNodeWithText("sonnet").assertIsDisplayed()
    }

    // ── reasoning pill gating ─────────────────────────────────────────────────────
    @Test fun reasoning_pill_hidden_when_not_visible() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = models,
                reasoning = ReasoningResponse(
                    agent = "claude", current = "high",
                    levels = listOf(ReasoningLevel("low"), ReasoningLevel("high")),
                    visible = false,
                ),
            )
        }
        onNodeWithTag("composer-reasoning-pill").assertDoesNotExist()
    }

    @Test fun reasoning_pill_hidden_when_a_single_level() = runComposeUiTest {
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = models,
                reasoning = ReasoningResponse(
                    agent = "claude", current = "high",
                    levels = listOf(ReasoningLevel("high")),
                    visible = true,
                ),
            )
        }
        onNodeWithTag("composer-reasoning-pill").assertDoesNotExist()
    }

    @Test fun reasoning_pill_shown_and_functional_when_visible_and_multi_level() = runComposeUiTest {
        var picked: String? = null
        setContent {
            DesktopComposer(
                draft = "", onDraftChange = {}, sending = false, agentWorking = false,
                onSend = { _, _ -> }, onInterrupt = {},
                models = models,
                reasoning = ReasoningResponse(
                    agent = "claude", current = "high",
                    levels = listOf(ReasoningLevel("low", "Low"), ReasoningLevel("high", "High")),
                    visible = true,
                ),
                onPickReasoning = { picked = it },
            )
        }
        onNodeWithTag("composer-reasoning-pill").assertIsDisplayed()
        // Short id on the pill — never the long description ("High" / "Greater reasoning depth").
        onNodeWithText("high").assertIsDisplayed()
        onNodeWithTag("composer-reasoning-pill").performClick()
        onNodeWithTag("composer-reasoning-low").performClick()
        assertEquals("low", picked)
    }

    @Test fun reasoning_label_uses_short_id_not_description() {
        val r = ReasoningResponse(
            agent = "claude",
            current = "xhigh",
            levels = listOf(
                ReasoningLevel("low", "Fast responses with lighter reasoning"),
                ReasoningLevel("xhigh", "Extra high reasoning depth"),
            ),
            visible = true,
        )
        assertEquals("xhigh", composerReasoningLabel(r))
        assertEquals("low", composerReasoningLevelLabel(r.levels[0]))
        assertEquals("effort", composerReasoningLabel(r.copy(current = null)))
    }

}
