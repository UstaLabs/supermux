package dev.supermux.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.ViewPlugin
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.editor.compose.viewPluginsFacet
import dev.supermux.editor.plugins.view.ViewSettings
import dev.supermux.net.FsEntry
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The panes on the native editor (M5 A3): borrowing views, reveal, zoom, settings, theme. */
@OptIn(ExperimentalTestApi::class)
class NativeEditorPaneTest {
    private val hundredLines = (1..100).joinToString("\n") { "line $it" }

    private fun storeWith(scope: kotlinx.coroutines.CoroutineScope, vararg files: Pair<String, String>, extra: ((Document) -> dev.supermux.editor.core.Extension)? = null): DocumentStore {
        val map = mapOf(*files)
        return DocumentStore({ p -> Result.success(map.getValue(p)) }, { _, _ -> true }, scope).also { s ->
            s.native = NativeEditorEnv(scope, extraExtensions = extra ?: { dev.supermux.editor.core.extensionOf() })
            for (f in map.keys) s.open(f)
        }
    }

    @Test fun a_pending_reveal_selects_its_line_centred_and_is_consumed() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.kt" to hundredLines)
        val doc = store.get("a.kt")!!
        doc.revealLine = 60 to null
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    NativeDocumentEditor(store, doc, lineWrap = false, fontSize = 13, onFontSize = {}, modifier = Modifier.size(400.dp, 300.dp))
                }
            }
        }
        waitForIdle()
        val view = doc.native!!.primary
        assertEquals(view.state.doc.lineStart(59), view.state.selection.main.head)
        assertNull(doc.revealLine)
        onNodeWithTag("editor_native").assertIsDisplayed()

        // A range reveal selects line..endLine, as cmRevealLine did.
        doc.revealLine = 10 to 12
        waitForIdle()
        val sel = view.state.selection.main
        assertEquals(view.state.doc.lineStart(9), sel.from)
        assertEquals(view.state.doc.lineStart(12) - 1, sel.to)
    }

    @Test fun switching_tabs_borrows_views_and_never_stops_them() = runComposeUiTest {
        var started = 0
        var destroyed = 0
        val lives = ViewPlugin { _ -> started++; object : ViewPluginInstance { override fun destroy() { destroyed++ } } }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.kt" to "aaa", "b.kt" to "bbb", extra = { viewPluginsFacet.of(lives) })
        var active by mutableStateOf("a.kt")
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                key(active) {
                    NativeDocumentEditor(store, store.get(active)!!, lineWrap = true, fontSize = 13, onFontSize = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        val a = assertNotNull(store.get("a.kt")?.native)
        val aView = a.primary
        active = "b.kt"; waitForIdle()
        active = "a.kt"; waitForIdle()
        active = "b.kt"; waitForIdle()

        assertSame(a, store.get("a.kt")?.native)
        assertSame(aView, a.primary)
        assertFalse(a.disposed)
        assertEquals(2, started)       // one instance per document, for its whole life
        assertEquals(0, destroyed)     // a tab switch stops nothing (no LSP didClose)
        store.close("a.kt")
        assertEquals(1, destroyed)
    }

    @Test fun a_zoom_is_persisted_as_a_whole_px_and_shows_the_badge() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.kt" to "zoom me")
        val doc = store.get("a.kt")!!
        val persisted = mutableListOf<Int>()
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    NativeDocumentEditor(store, doc, lineWrap = true, fontSize = 13, onFontSize = { persisted += it }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        mainClock.advanceTimeBy(100)
        doc.native!!.primary.zoomTo(15.4f)
        mainClock.advanceTimeBy(50)
        assertEquals(listOf(15), persisted)
        onNodeWithTag("editor_zoom_badge").assertTextEquals("15px")
        mainClock.advanceTimeBy(ZOOM_BADGE_MS + 100)
        onNodeWithTag("editor_zoom_badge").assertDoesNotExist()
    }

    @Test fun a_settings_change_reaches_the_live_view() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.kt" to "x")
        val doc = store.get("a.kt")!!
        var wrap by mutableStateOf(true)
        var font by mutableStateOf(13)
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                NativeDocumentEditor(store, doc, lineWrap = wrap, fontSize = font, onFontSize = {}, modifier = Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        wrap = false; font = 18
        waitForIdle()
        val settings = assertNotNull(ViewSettings.current(doc.native!!.primary.state))
        assertFalse(settings.lineWrap)
        assertEquals(18f, settings.fontSize)
    }

    @Test fun the_editor_theme_follows_the_app_light_and_dark() = runComposeUiTest {
        var appearance by mutableStateOf(AppearanceMode.LIGHT)
        val seen = mutableListOf<EditorTheme>()
        var expected: Pair<EditorTheme, EditorTheme>? = null
        setContent {
            val font = packagedEditorFontFamily()
            expected = EditorTheme.light(font) to EditorTheme.dark(font)
            SupermuxTheme(appearance = appearance) { seen += rememberAppEditorTheme() }
        }
        waitForIdle()
        assertEquals(expected!!.first.background, seen.last().background)
        appearance = AppearanceMode.DARK
        waitForIdle()
        assertEquals(expected!!.second.background, seen.last().background)
    }

    @Test fun the_composite_panel_draws_the_native_editor_and_its_theme_follows_the_app() = runComposeUiTest {
        val prefs = UiPrefs(InMemorySettingsStore())
        setContent {
            CompositionLocalProvider(
                LocalUiPrefs provides prefs,
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
                LocalPlatform provides FakePlatform(),
            ) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    EditorPanel(
                        state = EditorPanelState(sessionId = "s1", workdir = "/w"),
                        actions = EditorPanelActions(
                            fsList = { Result.success(listOf(FsEntry(name = "a.kt", type = "file"))) },
                            fsRead = { Result.success(hundredLines) },
                            fsWrite = { _, _ -> true },
                            fsSearch = { emptyList() },
                        ),
                        pendingOpen = PendingEditorOpen("a.kt", 3, null),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("editor_tab_a.kt").assertIsDisplayed()
        onNodeWithTag("editor_native").assertIsDisplayed()
        // Opening a file never writes the font size (only a zoom does).
        runBlocking { assertEquals(13, prefs.editorFontSize.first()) }
    }

    /** Review I8: under the markdown preview the editor is read-only (a hardware keyboard edits nothing). */
    @Test fun a_covered_editor_takes_no_edits() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.md" to "# title")
        val doc = store.get("a.md")!!
        var covered by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                NativeDocumentEditor(store, doc, lineWrap = true, fontSize = 13, onFontSize = {}, modifier = Modifier.fillMaxSize(), covered = covered)
            }
        }
        waitForIdle()
        covered = true
        waitForIdle()
        val view = doc.native!!.primary
        view.typeText("x")
        assertEquals("# title", view.state.doc.toString())
        covered = false
        waitForIdle()
        view.typeText("x")
        assertEquals("x# title", view.state.doc.toString())
    }

    /** Review I4: a reveal into a document shown BEFORE (its view kept a stale viewport) still lands centred. */
    @Test fun a_reveal_into_a_document_shown_before_lands_on_its_line() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val store = storeWith(scope, "a.kt" to hundredLines, "b.kt" to "b")
        var active by mutableStateOf("a.kt")
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides UiPrefs(InMemorySettingsStore())) {
                key(active) {
                    NativeDocumentEditor(store, store.get(active)!!, lineWrap = false, fontSize = 13, onFontSize = {}, modifier = Modifier.size(400.dp, 300.dp))
                }
            }
        }
        waitForIdle()
        active = "b.kt"; waitForIdle()
        val a = store.get("a.kt")!!
        a.revealLine = 80 to null
        active = "a.kt"; waitForIdle()
        val view = a.native!!.primary
        assertEquals(view.state.doc.lineStart(79), view.state.selection.main.head)
        // On screen: the top line is well above 80 and not the start of the file.
        val top = view.state.doc.lineIndexAt(view.scrollPosition.anchor)
        kotlin.test.assertTrue(top in 55..79, "line 80 revealed but the view shows line ${top + 1} at the top")
    }
}
