package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.media.MediaCache
import dev.supermux.media.MediaKey
import dev.supermux.media.MediaStore
import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.kit.KitAnchorKey
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * General (#97 spec §2.2, §10.6): theme, text size, live replies, chat detail and terminal colours on this device,
 * then "This app" — the version with the updater inline ([AppUpdateRowsTest] drives its phases) and the cache.
 * Absorbs the old Appearance and Storage screens' suites.
 *
 * The point of the look cases is that the page is the ONLY thing writing those keys and that a host reading them
 * back sees the change — "one source of truth" once desktop's sidebar theme toggle writes the same key.
 */
@OptIn(ExperimentalTestApi::class)
class GeneralPageTest {

    private fun anchored(key: String) = SemanticsMatcher.expectValue(KitAnchorKey, key)

    private fun scaleShows(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    // ── theme ─────────────────────────────────────────────────────────────────────────────────

    @Test fun choosing_a_theme_persists_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        assertNull(store.map.value[SettingsKeys.APPEARANCE])
        onNodeWithTag("general_theme_Light").performClick()
        waitForIdle()
        assertEquals("LIGHT", store.map.value[SettingsKeys.APPEARANCE])
        onNodeWithTag("general_theme_Dark").performClick()
        waitForIdle()
        assertEquals("DARK", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test fun the_theme_shows_what_the_app_paints_when_nothing_is_stored() = runComposeUiTest {
        setPlatformContent { GeneralPage(defaultAppearance = AppearanceMode.DARK) }
        onNodeWithTag("general_theme_Dark").assertIsSelected()
    }

    @Test fun the_host_default_applies_until_someone_chooses() = runComposeUiTest {
        // Desktop opens DARK with nothing stored; choosing SYSTEM must still be a real write rather than a no-op
        // against the default.
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage(defaultAppearance = AppearanceMode.DARK) }
        waitForIdle()
        onNodeWithTag("general_theme_Dark").assertIsSelected()
        onNodeWithTag("general_theme_System").performClick()
        waitForIdle()
        assertEquals("SYSTEM", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test fun a_stored_choice_beats_the_host_default() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.APPEARANCE to "LIGHT")
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage(defaultAppearance = AppearanceMode.DARK) }
        waitForIdle()
        onNodeWithTag("general_theme_Light").assertIsSelected()
    }

    /** A theme chosen here repaints the app, because the host's theme reads the same stored key. */
    @Test fun the_theme_follows_the_stored_choice() = runComposeUiTest {
        val store = FakeSettingsStore()
        val prefs = UiPrefs(store)
        setPlatformContent(uiPrefs = prefs) {
            val mode by prefs.appearance(AppearanceMode.DARK).collectAsState(AppearanceMode.DARK)
            SupermuxTheme(appearance = mode) {
                Column {
                    Text(
                        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) "light" else "dark",
                        modifier = Modifier.testTag("theme_probe"),
                    )
                    GeneralPage(defaultAppearance = AppearanceMode.DARK)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("theme_probe").assertTextEquals("dark")
        onNodeWithTag("general_theme_Light").performClick()
        waitForIdle()
        onNodeWithTag("theme_probe").assertTextEquals("light")
    }

    @Test fun material_you_is_gone() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(appearanceControls = true, dynamicColor = true))) { GeneralPage() }
        onNodeWithText("Material You").assertDoesNotExist()
    }

    // ── live replies (card #76) ───────────────────────────────────────────────────────────────

    @Test fun live_replies_are_on_until_turned_off_and_on_writes_nothing() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("appearance_live_replies_switch").assertIsOn()
        assertNull(store.map.value[SettingsKeys.LIVE_REPLIES], "the default writes nothing")
        onNodeWithTag("appearance_live_replies_switch").performClick()
        waitForIdle()
        onNodeWithTag("appearance_live_replies_switch").assertIsOff()
        assertEquals("false", store.map.value[SettingsKeys.LIVE_REPLIES])
        onNodeWithTag("appearance_live_replies_switch").performClick()
        waitForIdle()
        onNodeWithTag("appearance_live_replies_switch").assertIsOn()
        assertNull(store.map.value[SettingsKeys.LIVE_REPLIES], "back on clears the key")
    }

    // ── chat detail, terminal colours ─────────────────────────────────────────────────────────

    @Test fun chat_detail_writes_the_level() = runComposeUiTest {
        val store = FakeSettingsStore()
        val prefs = UiPrefs(store)
        setPlatformContent(uiPrefs = prefs) { GeneralPage() }
        onNodeWithTag("general_chat-detail_Medium").assertIsSelected()
        onNodeWithTag("general_chat-detail_High").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { prefs.chatDetailLevel.first() } == ChatDetailLevel.HIGH }
        onNodeWithTag("general_chat-detail_High").assertIsSelected()
    }

    @Test fun choosing_a_terminal_mode_persists_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("general_terminal-colours_Match app").assertIsSelected()
        onNodeWithTag("general_terminal-colours_Light").performScrollTo().performClick()
        waitForIdle()
        assertEquals("LIGHT", store.map.value[SettingsKeys.TERMINAL_APPEARANCE])
        onNodeWithTag("general_terminal-colours_Light").assertIsSelected()
    }

    @Test fun every_row_carries_its_anchor() = runComposeUiTest {
        setPlatformContent { GeneralPage(cache = cacheOf(SizedStore(1))) }
        for (a in listOf("theme", "text-size", "live-replies", "chat-detail", "terminal-colours", "app-version", "cache")) {
            onNode(anchored(a)).assertExists()
        }
    }

    // ── text scale ────────────────────────────────────────────────────────────────────────────

    @Test fun a_stored_text_scale_is_shown_as_a_percentage() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "1.2")
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("120%"))
        onNodeWithText("Scales all text in the app.").assertExists()
    }

    @Test fun an_out_of_range_text_scale_is_read_back_clamped() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "5.0")
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("130%"))
    }

    /** The theme multiplies `fontScale`: a stored scale changes the rendered size of a line of text. */
    @Test fun the_text_scale_applies_to_the_theme() = runComposeUiTest {
        val store = FakeSettingsStore()
        val prefs = UiPrefs(store)
        setPlatformContent(uiPrefs = prefs) {
            val scale by prefs.textScale.collectAsState(TEXT_SCALE_DEFAULT)
            SupermuxTheme(textScale = scale) {
                Text("probe", modifier = Modifier.testTag("scale_probe"))
            }
        }
        waitForIdle()
        val before = onNodeWithTag("scale_probe").fetchSemanticsNode().size.height
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "1.3")
        waitForIdle()
        val after = onNodeWithTag("scale_probe").fetchSemanticsNode().size.height
        assertNotEquals(before, after, "text scale did not reach the theme")
        kotlin.test.assertTrue(after > before, "1.3 should render taller than 1.0 (was $before → $after)")
    }

    /**
     * The slider persists when the finger lifts, not on every step of a drag (a disk write per frame, and the knob
     * following the store instead of the finger). Accessibility's setProgress runs both lambdas, as a drag does.
     */
    @Test fun changing_the_text_scale_persists_the_new_value_on_release() = runComposeUiTest {
        val store = CountingSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        assertEquals(0, store.writes)
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("100%"))

        onNodeWithTag("appearance_text_scale_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1.3f) }
        waitForIdle()
        assertEquals(1, store.writes)
        assertEquals(1.3f, store.map.value[SettingsKeys.TEXT_SCALE]?.toFloat())
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("130%"))

        // The local drag value is dropped once the store has caught up, so a change made elsewhere still lands.
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "0.9")
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("90%"))
    }

    /** A drag that ENDS on the stored value writes nothing, so the local override must be cleared by hand. */
    @Test fun a_drag_that_ends_on_the_stored_value_drops_the_local_override() = runComposeUiTest {
        val store = DroppingSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("100%"))

        onNodeWithTag("appearance_text_scale_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1.3f) }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("130%"))

        onNodeWithTag("appearance_text_scale_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("100%"))

        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "0.9")
        waitForIdle()
        onNodeWithTag("appearance_text_scale_slider").assert(scaleShows("90%"))
    }

    // ── this app ──────────────────────────────────────────────────────────────────────────────

    @Test fun web_and_ios_show_the_version_without_a_button() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(caps = NO_CAPS.copy(appUpdate = false))) { GeneralPage() }
        onNodeWithTag("app_update_version").performScrollTo().assertTextEquals("supermux 1.0.0")
        onNodeWithTag("general_check_updates").assertDoesNotExist()
    }

    @Test fun the_updater_runs_inline_where_the_app_updates_itself() = runComposeUiTest {
        val updater = FakeAppUpdater(release = null)
        val platform = FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater }
        setPlatformContent(platform = platform) { GeneralPage() }
        waitForIdle()
        onNodeWithTag("app_update_message").performScrollTo().assertTextEquals("Couldn't check for updates.")
    }

    @Test fun the_cache_shows_its_size_and_clear_empties_it() = runComposeUiTest {
        val store = SizedStore(size = 2_500_000)
        setPlatformContent { GeneralPage(cache = cacheOf(store)) }
        waitUntil(timeoutMillis = 5_000L) {
            runCatching { onNodeWithTag("storage_size").assertTextEquals("2 MB, kept so they open instantly and offline.") }.isSuccess
        }
        onNodeWithText("Cached images and files").assertExists()
        onNodeWithTag("storage_clear").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("storage_size").assertTextEquals("Empty") }.isSuccess }
        assertEquals(1, store.clears)
        onNodeWithTag("storage_clear").assertIsNotEnabled()
    }

    @Test fun a_memory_only_cache_says_so() = runComposeUiTest {
        setPlatformContent { GeneralPage(cache = cacheOf(SizedStore(size = 0, persistent = false))) }
        onNodeWithText("only until this page closes", substring = true).assertExists()
    }

    @Test fun no_cache_no_row() = runComposeUiTest {
        setPlatformContent { GeneralPage() }
        onNodeWithTag("storage_clear").assertDoesNotExist()
        onNode(anchored("cache")).assertDoesNotExist()
    }

    // ── language (#123) ───────────────────────────────────────────────────────────────────────

    @Test fun the_language_follows_the_system_until_someone_chooses() = runComposeUiTest {
        setPlatformContent { GeneralPage() }
        onNodeWithTag("general_language_system").assertIsSelected()
    }

    @Test fun choosing_a_language_persists_it_and_system_clears_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { SupermuxTheme { GeneralPage() } }
        waitForIdle()
        onNodeWithTag("general_language_tr").performScrollTo().performClick()
        waitForIdle()
        assertEquals("tr", store.map.value[SettingsKeys.LANGUAGE])
        onNodeWithTag("general_language_system").performScrollTo().performClick()
        waitForIdle()
        assertNull(store.map.value[SettingsKeys.LANGUAGE])
    }

    /** The theme reads the stored language, so choosing Türkçe repaints this very page in Turkish. */
    @Test fun choosing_turkish_shows_the_page_in_turkish() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { SupermuxTheme { GeneralPage() } }
        onNodeWithText("Theme").assertExists()
        onNodeWithTag("general_language_tr").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Tema").assertExists()
        onNodeWithText("Theme").assertDoesNotExist()
        // Tags never translate: UI automation finds the same option in every language.
        onNodeWithTag("general_theme_Light").assertExists()
        onNodeWithTag("general_language_tr").assertIsSelected()
    }

    @Test fun a_stored_language_applies_on_open() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.LANGUAGE to "tr")
        setPlatformContent(uiPrefs = UiPrefs(store)) { SupermuxTheme { GeneralPage() } }
        waitForIdle()
        onNodeWithText("Yazı boyutu").assertExists()
    }

    @Test fun the_page_is_general_on_this_device() = runComposeUiTest {
        setPlatformContent { GeneralPage() }
        onNodeWithTag("appearance_settings_screen").assertIsDisplayed()
        onNodeWithText("Only this device").assertExists()
    }
}

internal fun cacheOf(store: MediaStore) = MediaCache(store, CoroutineScope(Dispatchers.Default))

/** Just enough of a store for the cache row: a size, and a Clear that empties it. */
internal class SizedStore(var size: Long, override val persistent: Boolean = true) : MediaStore {
    var clears = 0
    override val hasFiles: Boolean get() = true
    override suspend fun read(key: MediaKey): ByteArray? = null
    override suspend fun exists(key: MediaKey): Boolean = false
    override suspend fun uri(key: MediaKey): String? = null
    override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean = false
    override suspend fun writeStream(
        key: MediaKey,
        fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean,
    ): Boolean = false
    override suspend fun delete(key: MediaKey) = Unit
    override suspend fun removeHost(host: String) = Unit
    override suspend fun clear() {
        clears++
        size = 0
    }
    override suspend fun sizeBytes(): Long = size
}

/** A store whose writes never land — stands in for a DataStore that cannot persist. */
private class DroppingSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) = Unit
}

/** [FakeSettingsStore] that counts writes, so "once per drag" is assertable. */
private class CountingSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    var writes = 0
        private set

    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        writes++
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}
