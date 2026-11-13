package dev.supermux.ui.whatsnew

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.prefs.FakeSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** The card as the shell mounts it (#90): it decides at launch, counts this platform's chapters, and settles once. */
@OptIn(ExperimentalTestApi::class)
class WhatsNewSlotTest {

    private val page = """<script type="application/json" id="edition">
        {"version":"0.18.0","chapters":[{"id":"a"},{"id":"b","on":["win"]},{"id":"c"}]}</script>"""

    private fun androidx.compose.ui.test.ComposeUiTest.mount(
        store: FakeSettingsStore,
        existing: Boolean = true,
        opened: MutableList<String> = mutableListOf(),
        platform: ReleasePlatform? = ReleasePlatform.Mac,
    ) =
        setPlatformContent {
            val whatsNew = rememberWhatsNew(store, "0.18.0", platform, existingInstall = { existing }, load = { page })
            WhatsNewSlot(whatsNew, onOpen = { opened += it })
        }

    @Test fun an_update_shows_the_card_with_this_platforms_chapters() = runComposeUiTest {
        mount(FakeSettingsStore())
        waitUntil { onAllNodes(androidx.compose.ui.test.hasTestTag("whats_new_card")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2 new things you can do on this Mac.").assertExists()
    }

    @Test fun a_build_that_does_not_say_which_platform_it_is_is_offered_nothing() = runComposeUiTest {
        val store = FakeSettingsStore()
        mount(store, platform = null)
        waitForIdle()
        onNodeWithTag("whats_new_card").assertDoesNotExist()
        assertEquals(null, store.map.value[WhatsNewTracker.SEEN_KEY], "nothing decided for it either")
    }

    @Test fun a_first_install_shows_nothing() = runComposeUiTest {
        val store = FakeSettingsStore()
        mount(store, existing = false)
        waitForIdle()
        onNodeWithTag("whats_new_card").assertDoesNotExist()
        assertEquals("0.18.0", store.map.value[WhatsNewTracker.SEEN_KEY])
    }

    @Test fun explore_opens_the_edition_and_settles_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        val opened = mutableListOf<String>()
        mount(store, opened = opened)
        waitUntil { onAllNodes(androidx.compose.ui.test.hasTestTag("whats_new_card_explore")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("whats_new_card_explore").performClick()
        waitForIdle()
        assertEquals(listOf("0.18.0"), opened)
        assertEquals("0.18.0", store.map.value[WhatsNewTracker.SEEN_KEY])
        onNodeWithTag("whats_new_card").assertDoesNotExist()
    }

    @Test fun dismiss_settles_it_without_opening() = runComposeUiTest {
        val store = FakeSettingsStore()
        val opened = mutableListOf<String>()
        mount(store, opened = opened)
        waitUntil { onAllNodes(androidx.compose.ui.test.hasTestTag("whats_new_card_dismiss")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("whats_new_card_dismiss").performClick()
        waitForIdle()
        assertEquals(emptyList(), opened)
        assertEquals("0.18.0", store.map.value[WhatsNewTracker.SEEN_KEY])
    }
}
