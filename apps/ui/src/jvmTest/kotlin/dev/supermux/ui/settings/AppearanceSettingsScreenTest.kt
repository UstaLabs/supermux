package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The shared Appearance screen (cluster E7): Android's `AppearanceSettingsPage`, with the three
 * values moved out of `MainActivity`'s SharedPreferences into `SettingsKeys.APPEARANCE` /
 * `DYNAMIC_COLOR` / `TEXT_SCALE`, which is what makes it renderable on desktop at all.
 *
 * The point of every case below is that the screen is the ONLY thing writing those keys and that a
 * host reading them back sees the change — that is what "one source of truth" means once desktop's
 * sidebar theme toggle writes the same key.
 */
@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsScreenTest {

    private val dynamicCaps = NO_CAPS.copy(appearanceControls = true, dynamicColor = true)

    // ── theme ─────────────────────────────────────────────────────────────────────────────────

    @Test fun choosing_a_theme_persists_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { AppearanceSettingsScreen() }
        waitForIdle()
        assertNull(store.map.value[SettingsKeys.APPEARANCE])
        onNodeWithTag("appearance_mode_light").performClick()
        waitForIdle()
        assertEquals("LIGHT", store.map.value[SettingsKeys.APPEARANCE])
        onNodeWithTag("appearance_mode_dark").performClick()
        waitForIdle()
        assertEquals("DARK", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test fun the_host_default_applies_until_someone_chooses() = runComposeUiTest {
        // Desktop opens DARK with nothing stored; the row it shows selected must follow that, and
        // choosing SYSTEM must still be a real write rather than a no-op against the default.
        val store = FakeSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) {
            AppearanceSettingsScreen(defaultAppearance = AppearanceMode.DARK)
        }
        waitForIdle()
        onNodeWithTag("appearance_mode_dark").assertIsSelected()
        onNodeWithTag("appearance_mode_system").performClick()
        waitForIdle()
        assertEquals("SYSTEM", store.map.value[SettingsKeys.APPEARANCE])
    }

    @Test fun a_stored_choice_beats_the_host_default() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.APPEARANCE to "LIGHT")
        setPlatformContent(uiPrefs = UiPrefs(store)) {
            AppearanceSettingsScreen(defaultAppearance = AppearanceMode.DARK)
        }
        waitForIdle()
        onNodeWithTag("appearance_mode_light").assertIsSelected()
    }

    /**
     * The point of the whole move: a theme chosen on this screen repaints the app, because the
     * host's theme reads the same stored key. Stand in for a host root here — a `SupermuxTheme`
     * driven by `UiPrefs.appearance` — and prove the colour scheme actually flips.
     */
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
                    AppearanceSettingsScreen(defaultAppearance = AppearanceMode.DARK)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("theme_probe").assertTextEquals("dark")
        onNodeWithTag("appearance_mode_light").performClick()
        waitForIdle()
        onNodeWithTag("theme_probe").assertTextEquals("light")
    }

    // ── Material You gate ─────────────────────────────────────────────────────────────────────

    @Test fun the_material_you_row_is_hidden_without_the_capability() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            platform = FakePlatform(caps = NO_CAPS.copy(appearanceControls = true)),
            uiPrefs = UiPrefs(store),
        ) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_settings_screen").assertIsDisplayed()
        onNodeWithTag("appearance_dynamic_row").assertDoesNotExist()
    }

    @Test fun the_material_you_row_shows_and_persists_where_the_os_can_do_it() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            platform = FakePlatform(caps = dynamicCaps),
            uiPrefs = UiPrefs(store),
        ) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_dynamic_row").assertIsDisplayed()
        onNodeWithTag("appearance_dynamic_switch").performClick()
        waitForIdle()
        assertEquals("true", store.map.value[SettingsKeys.DYNAMIC_COLOR])
    }

    // ── text scale ────────────────────────────────────────────────────────────────────────────

    @Test fun a_stored_text_scale_is_shown_as_a_percentage() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "1.2")
        setPlatformContent(uiPrefs = UiPrefs(store)) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("120%")
    }

    @Test fun an_out_of_range_text_scale_is_read_back_clamped() = runComposeUiTest {
        val store = FakeSettingsStore()
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "5.0")
        setPlatformContent(uiPrefs = UiPrefs(store)) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("130%")
    }

    /**
     * Live text scale: the same `SupermuxTheme` stand-in, this time probing that a stored scale
     * actually changes the rendered size of a line of text (the theme multiplies `fontScale`).
     */
    @Test fun the_text_scale_applies_live() = runComposeUiTest {
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

    // ── chrome ────────────────────────────────────────────────────────────────────────────────

    @Test fun a_compact_window_paints_its_own_top_bar_when_the_hub_did_not() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_settings_back").assertIsDisplayed()
    }

    @Test fun the_hub_owns_the_bar_when_it_says_so() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            uiPrefs = UiPrefs(store),
        ) { AppearanceSettingsScreen(topBarShown = true) }
        waitForIdle()
        onNodeWithTag("appearance_settings_back").assertDoesNotExist()
        onNodeWithTag("appearance_settings_screen").assertIsDisplayed()
    }

    @Test fun a_standalone_destination_paints_the_bar_at_every_width() = runComposeUiTest {
        val store = FakeSettingsStore()
        setPlatformContent(widthClass = WindowWidthClass.Expanded, uiPrefs = UiPrefs(store)) {
            AppearanceSettingsScreen(standalone = true)
        }
        waitForIdle()
        onNodeWithTag("appearance_settings_back").assertIsDisplayed()
    }

    /**
     * The slider persists through `onValueChangeFinished`, not `onValueChange`: a drag would
     * otherwise commit to disk on every frame of the gesture (a DataStore write per frame on
     * Android) and the thumb would follow the persisted value rather than the finger. The drag
     * itself is held in local state — see `draggedTextScale` — and this drives the Slider's own
     * value-change path end to end, asserting that the release wrote once and that the screen and
     * the store agree afterwards.
     *
     * (A synthetic multi-event drag does not move an M3 Slider under the skiko test harness, so
     * the semantics action is what actually exercises the two lambdas here.)
     */
    @Test fun changing_the_text_scale_persists_the_new_value_on_release() = runComposeUiTest {
        val store = CountingSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { AppearanceSettingsScreen() }
        waitForIdle()
        assertEquals(0, store.writes)
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("100%")

        onNodeWithTag("appearance_text_scale_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.3f) }
        waitForIdle()
        assertEquals(1, store.writes)
        assertEquals(1.3f, store.map.value[SettingsKeys.TEXT_SCALE]?.toFloat())
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("130%")

        // The local drag value is dropped once the store has caught up, so a change made anywhere
        // else still reaches this screen.
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "0.9")
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("90%")
    }

    /**
     * E7 re-review minor: a drag that ENDS on the stored value writes nothing, so the effect that
     * normally drops the local override never fires — without an explicit clear the screen would
     * be stuck on its own value and stop following changes made anywhere else, for good.
     *
     * The store here drops its writes (a failing DataStore) purely so the drag can be made to land
     * back on the unchanged stored value; the assertion is about the local override's lifecycle.
     */
    @Test fun a_drag_that_ends_on_the_stored_value_drops_the_local_override() = runComposeUiTest {
        val store = DroppingSettingsStore()
        setPlatformContent(uiPrefs = UiPrefs(store)) { AppearanceSettingsScreen() }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("100%")

        // Drag away — the write is dropped, so only the LOCAL value moves.
        onNodeWithTag("appearance_text_scale_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.3f) }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("130%")

        // …and back onto the stored value: nothing to persist, so the override must be cleared.
        onNodeWithTag("appearance_text_scale_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("100%")

        // Proof it was cleared: an outside change still reaches the screen.
        store.map.value = mapOf(SettingsKeys.TEXT_SCALE to "0.9")
        waitForIdle()
        onNodeWithTag("appearance_text_scale_value").assertTextEquals("90%")
    }
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
