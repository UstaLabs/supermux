package dev.supermux.ui.settings.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KitRowsTest {
    @Test fun a_toggle_row_is_one_switch_carrying_its_label() = runComposeUiTest {
        var on by mutableStateOf(true)
        setPlatformContent {
            KitToggleRow("Wrap long lines", on, { on = it }, Modifier.testTag("wrap"), description = "Wrap instead of scrolling sideways.")
        }
        onNodeWithTag("wrap").assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        onNodeWithText("Wrap long lines").performClick()
        onNodeWithTag("wrap").assertIsOff()
    }

    @Test fun an_options_row_stacks_its_options_under_the_label_on_a_phone() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            KitOptionsRow("Theme", listOf("System", "Light", "Dark"), "Dark", {}, { it }, optionTag = { "o_$it" })
        }
        val label = onNodeWithText("Theme").getBoundsInRoot()
        val option = onNodeWithTag("o_Dark").getBoundsInRoot()
        assertTrue(option.top >= label.bottom, "options should sit under the label on a phone")
    }

    @Test fun an_options_row_keeps_its_options_beside_the_label_when_wide() = runComposeUiTest {
        setPlatformContent {
            KitOptionsRow("Theme", listOf("System", "Light", "Dark"), "Dark", {}, { it }, optionTag = { "o_$it" })
        }
        val label = onNodeWithText("Theme").getBoundsInRoot()
        val option = onNodeWithTag("o_Dark").getBoundsInRoot()
        assertTrue(option.left > label.right, "options should sit at the end of the row when wide")
    }

    @Test fun a_value_row_with_a_destination_clicks() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitValueRow("Words to spell your way", "24 words", Modifier.testTag("v"), onClick = { n++ }) }
        onNodeWithTag("v").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals(1, n)
    }

    @Test fun an_action_row_runs_its_button() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitActionRow("Last tidy-up", "Run now", { n++ }, description = "Never", buttonTag = "run") }
        onNodeWithTag("run").performClick()
        assertEquals(1, n)
    }

    @Test fun a_fold_opens_and_closes_and_says_so() = runComposeUiTest {
        setPlatformContent {
            Column { KitFold("Advanced", tag = "adv") { KitValueRow("Tidy-up model", "Codex") } }
        }
        onNodeWithText("Tidy-up model").assertDoesNotExist()
        onNodeWithTag("adv").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        onNodeWithTag("adv").performClick()
        onNodeWithText("Tidy-up model").assertExists()
        onNodeWithTag("adv").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test fun a_fold_opens_itself_when_search_targets_a_row_inside_it() = runComposeUiTest {
        val anchors = KitAnchors(target = "tidy-up-model")
        setPlatformContent {
            CompositionLocalProvider(LocalKitAnchors provides anchors) {
                Column { KitFold("Advanced", anchors = setOf("tidy-up-model")) { KitValueRow("Tidy-up model", "Codex") } }
            }
        }
        onNodeWithText("Tidy-up model").assertExists()
    }

    @Test fun an_item_shows_title_subtitle_and_status_and_opens() = runComposeUiTest {
        var n = 0
        setPlatformContent {
            KitItemList {
                KitItem("Claude Code", Modifier.testTag("i"), subtitle = "Your login · you@example.com", mark = KitMark.Letters("C"), onClick = { n++ }) {
                    KitItemStatus("Ready", KitDot.Ok)
                }
            }
        }
        onNodeWithText("Your login · you@example.com").assertExists()
        onNodeWithText("Ready").assertExists()
        onNodeWithTag("i").performClick()
        assertEquals(1, n)
    }

    @Test fun a_section_head_writes_its_text_in_capitals_and_its_note_as_is() = runComposeUiTest {
        setPlatformContent { KitSectionHead("Code intelligence", note = "on studio-mini") }
        onNodeWithText("CODE INTELLIGENCE").assertExists()
        onNodeWithText("on studio-mini").assertExists()
    }
}
