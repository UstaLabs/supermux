package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Voice ▸ Words to spell your way (#97 spec §2.2): the word list with Remove on each word and "Add a word"; every
 * change saves the whole list. A failed load is never shown (or saved) as an empty list.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class GlossaryPageTest {

    private fun actions(
        glossaryLoad: suspend () -> List<String>? = { listOf("Supermux", "BrokerApi") },
        glossarySave: suspend (List<String>) -> List<String>? = { it },
    ) = VoiceSettingsActions(glossaryLoad = glossaryLoad, glossarySave = glossarySave)

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitGone(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun ComposeUiTest.typeAWord(word: String) {
        awaitTag("voice_glossary_add_item")
        onNodeWithTag("voice_glossary_add_item").performClick()
        awaitTag("voice_glossary_input")
        onNodeWithTag("voice_glossary_input").performTextInput(word)
    }

    @Test fun the_words_are_listed_with_remove() = runComposeUiTest {
        setPlatformContent { GlossaryPage(actions()) }
        awaitTag("voice_glossary_term_Supermux")
        onNodeWithTag("voice_glossary_page").assertExists()
        onNodeWithText("Words to spell your way").assertExists()
        onNodeWithTag("voice_glossary_term_BrokerApi").assertExists()
        onNodeWithTag("voice_glossary_remove_Supermux").assertExists()
        onNodeWithText("Add a word").assertExists()
        // The field shows only once "Add a word" is picked.
        onNodeWithTag("voice_glossary_input").assertDoesNotExist()
    }

    @Test fun adding_a_word_saves_the_new_list() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<String>>()
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { listOf("Supermux") }, glossarySave = { saves += it; it })) }
        typeAWord("BrokerApi")
        onNodeWithTag("voice_glossary_add").performClick()
        awaitTag("voice_glossary_term_BrokerApi")
        assertEquals(listOf(listOf("Supermux", "BrokerApi")), saves.toList())
    }

    @Test fun enter_adds_the_word() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<String>>()
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { emptyList() }, glossarySave = { saves += it; it })) }
        typeAWord("Kotlin")
        onNodeWithTag("voice_glossary_input").performImeAction()
        awaitTag("voice_glossary_term_Kotlin")
        assertEquals(listOf(listOf("Kotlin")), saves.toList())
    }

    @Test fun a_word_already_there_is_not_added_twice() = runComposeUiTest {
        val saves = CopyOnWriteArrayList<List<String>>()
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { listOf("Supermux") }, glossarySave = { saves += it; it })) }
        typeAWord("supermux")
        onNodeWithTag("voice_glossary_add").performClick()
        waitForIdle()
        assertEquals(emptyList(), saves.toList())
    }

    @Test fun removing_a_word_saves_the_new_list() = runComposeUiTest {
        val saved = AtomicReference(listOf("Supermux", "BrokerApi"))
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { saved.get() }, glossarySave = { saved.set(it); it })) }
        awaitTag("voice_glossary_remove_Supermux")
        onNodeWithTag("voice_glossary_remove_Supermux").performClick()
        awaitGone("voice_glossary_term_Supermux")
        assertEquals(listOf("BrokerApi"), saved.get())
        onNodeWithTag("voice_glossary_remove_BrokerApi").performClick()
        awaitTag("voice_glossary_empty")
        assertEquals(emptyList(), saved.get())
    }

    @Test fun a_failed_save_shows_what_the_machine_still_has() = runComposeUiTest {
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { listOf("Keep") }, glossarySave = { null })) }
        typeAWord("Temp")
        onNodeWithTag("voice_glossary_add").performClick()
        awaitTag("voice_glossary_error")
        onNodeWithText("Couldn't save — reverted").assertExists()
        onNodeWithTag("voice_glossary_term_Keep").assertExists()
        awaitGone("voice_glossary_term_Temp")
    }

    @Test fun a_failed_load_is_an_error_not_an_empty_list() = runComposeUiTest {
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { null })) }
        awaitTag("voice_glossary_load_error")
        onNodeWithText("Couldn't reach this machine.").assertExists()
        onNodeWithTag("voice_glossary_retry").assertExists()
        onNodeWithTag("voice_glossary_empty").assertDoesNotExist()
        // Nothing to add to until it loads: one word would replace the real list.
        onNodeWithTag("voice_glossary_add_item").assertDoesNotExist()
    }

    @Test fun an_empty_list_says_so_and_offers_to_add() = runComposeUiTest {
        setPlatformContent { GlossaryPage(actions(glossaryLoad = { emptyList() })) }
        awaitTag("voice_glossary_empty")
        onNodeWithTag("voice_glossary_load_error").assertDoesNotExist()
        onNodeWithTag("voice_glossary_add_item").assertExists()
    }

    @Test fun wide_it_has_a_way_back_to_voice() = runComposeUiTest {
        var backs = 0
        setPlatformContent { GlossaryPage(actions(), onBack = { backs++ }) }
        awaitTag("settings_page_back")
        onNodeWithText("Voice").assertExists()
        onNodeWithTag("settings_page_back").performClick()
        assertEquals(1, backs)
    }

    @Test fun on_a_phone_the_top_bar_has_the_way_back() = runComposeUiTest {
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            GlossaryPage(actions())
        }
        awaitTag("voice_glossary_term_Supermux")
        onNodeWithTag("settings_page_back").assertDoesNotExist()
    }

    /** No lazy list on a Settings page: a list longer than the screen still reaches its last word. */
    @Test fun a_long_list_scrolls_to_its_last_word() = runComposeUiTest {
        val saved = AtomicReference((1..40).map { "Term$it" })
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            GlossaryPage(actions(glossaryLoad = { saved.get() }, glossarySave = { saved.set(it); it }))
        }
        awaitTag("voice_glossary_term_Term40")
        onNodeWithTag("voice_glossary_term_Term40").assertIsNotDisplayed()
        onNodeWithTag("voice_glossary_term_Term40").performScrollTo().assertIsDisplayed()
        onNodeWithTag("voice_glossary_remove_Term40").performScrollTo().performClick()
        awaitGone("voice_glossary_term_Term40")
        assertEquals(39, saved.get().size)
    }

    @Test fun an_offline_machine_shows_the_offline_line_and_loads_nothing() = runComposeUiTest {
        val off = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2h ago")
        setPlatformContent {
            CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(off), "m2") {}) {
                GlossaryPage(actions(glossaryLoad = { error("must not load for an offline machine") }))
            }
        }
        waitForIdle()
        onNodeWithText("studio-mini is offline · last seen 2h ago.").assertExists()
        onNodeWithTag("voice_glossary_add_item").assertDoesNotExist()
    }

    /** B2: the word list's GET failing with 500 is a failed load (null), never an empty list a save would write. */
    @Test fun a_host_store_load_failing_with_500_is_an_error() = runComposeUiTest {
        val engine = MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (req.url.encodedPath == "/config/voice-glossary") respond("{}", HttpStatusCode.InternalServerError, json) else respond("{}", HttpStatusCode.OK, json)
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = HostStoreDeps(httpFactory = { client }, settings = FakeSettingsStore(), clock = FixedClock()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        var result: List<String>? = emptyList()
        setPlatformContent {
            GlossaryPage(
                VoiceSettingsActions(
                    glossaryLoad = {
                        result = app.fetchGlossary()
                        result
                    },
                    glossarySave = { app.updateGlossary(it) },
                ),
            )
        }
        awaitTag("voice_glossary_load_error")
        assertNull(result)
        onNodeWithTag("voice_glossary_empty").assertDoesNotExist()
    }
}
