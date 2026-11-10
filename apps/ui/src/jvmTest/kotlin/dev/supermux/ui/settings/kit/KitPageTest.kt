package dev.supermux.ui.settings.kit

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KitPageTest {
    private fun anchor(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    @Test fun a_wide_page_shows_its_title_scope_and_rows() = runComposeUiTest {
        setPlatformContent {
            KitPage("Memory", scope = { KitMachineScope() }) { KitToggleRow("Tidy up memory every night", false, {}) }
        }
        onNodeWithTag("settings_page_title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
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

    @Test fun the_flash_fades_even_when_the_page_loses_its_anchor_mid_flash() = runComposeUiTest {
        // The hub clears its saved anchor in onAnchorShown, so the page recomposes with anchor = null while lit.
        var anchor by mutableStateOf<String?>("row-3")
        var anchors: KitAnchors? = null
        setPlatformContent {
            KitPage("Short", anchor = anchor, onAnchorShown = { anchor = null }) {
                anchors = LocalKitAnchors.current
                for (i in 0 until 5) KitValueRow("Row $i", "v", Modifier.kitAnchor("row-$i"))
            }
        }
        waitForIdle()
        assertEquals(null, anchor, "the page reported the anchor as shown")
        mainClock.advanceTimeBy(1_600)
        waitForIdle()
        assertEquals(null, anchors!!.flashing)
    }

    @Test fun a_row_that_loads_late_is_still_brought_into_view_and_flashed() = runComposeUiTest {
        var shown = 0
        var flashed: String? = null
        var anchors: KitAnchors? = null
        setPlatformContent {
            KitPage("Long", anchor = "late", onAnchorShown = { shown++; flashed = anchors?.flashing }) {
                anchors = LocalKitAnchors.current
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(500)
                    loaded = true
                }
                for (i in 0 until 60) KitValueRow("Row $i", "v")
                if (loaded) KitValueRow("Late row", "v", Modifier.kitAnchor("late"))
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNode(anchor("late")).assertIsDisplayed()
        assertEquals(1, shown)
        assertEquals("late", flashed)
    }

    @Test fun an_anchor_that_never_appears_is_reported_without_a_flash() = runComposeUiTest {
        var shown = 0
        var flashed: String? = "never called"
        var anchors: KitAnchors? = null
        setPlatformContent {
            KitPage("Short", anchor = "missing", onAnchorShown = { shown++; flashed = anchors?.flashing }) {
                anchors = LocalKitAnchors.current
                KitValueRow("Row", "v", Modifier.kitAnchor("row"))
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(3_100)
        waitForIdle()
        assertEquals(1, shown)
        assertEquals(null, flashed)
        assertEquals(null, anchors!!.flashing)
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
