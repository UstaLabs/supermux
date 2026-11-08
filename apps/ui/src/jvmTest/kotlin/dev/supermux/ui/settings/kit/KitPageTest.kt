package dev.supermux.ui.settings.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KitPageTest {
    private fun anchor(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    @Test fun a_wide_page_shows_its_title_scope_and_rows() = runComposeUiTest {
        setPlatformContent {
            KitPage("Memory", scope = { KitMachineScope() }) { KitToggleRow("Tidy up memory every night", false, {}) }
        }
        onNodeWithTag("settings_page_title").assertExists()
        onNodeWithTag("settings_scope").assertExists()
        onNodeWithText("Tidy up memory every night").assertExists()
    }

    @Test fun a_phone_page_leaves_the_title_to_the_top_bar() = runComposeUiTest {
        setPlatformContent(widthClass = WindowWidthClass.Compact, pointer = false) {
            KitPage("Memory") { KitValueRow("At", "01:00") }
        }
        onNodeWithTag("settings_page_title").assertDoesNotExist()
    }

    @Test fun opening_for_an_anchor_scrolls_it_into_view_and_reports_it() = runComposeUiTest {
        var shown = 0
        setPlatformContent {
            KitPage("Long", anchor = "row-50", onAnchorShown = { shown++ }) {
                for (i in 0 until 60) KitValueRow("Row $i", "v", Modifier.kitAnchor("row-$i"))
            }
        }
        waitForIdle()
        onNode(anchor("row-50")).assertIsDisplayed()
        assertEquals(1, shown)
    }

    @Test fun an_anchor_inside_a_fold_opens_the_fold() = runComposeUiTest {
        setPlatformContent {
            KitPage("Voice", anchor = "tidy-up-model") {
                KitFold("Advanced", anchors = setOf("tidy-up-model")) {
                    KitValueRow("Tidy-up model", "Codex", Modifier.kitAnchor("tidy-up-model"))
                }
            }
        }
        waitForIdle()
        onNode(anchor("tidy-up-model")).assertExists()
    }

    @Test fun a_wide_sub_page_offers_its_way_back() = runComposeUiTest {
        var back = 0
        setPlatformContent {
            KitPage("Claude Code", backLabel = "Providers & accounts", onBack = { back++ }) {}
        }
        onNodeWithTag("settings_page_back").performClick()
        assertEquals(1, back)
    }
}
