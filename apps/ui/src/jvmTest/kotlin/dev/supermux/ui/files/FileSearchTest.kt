package dev.supermux.ui.files

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.fs.FileSystemService
import dev.supermux.fs.SearchHit
import dev.supermux.net.BrokerApi
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FileSearchTest {

    // ── pure ──────────────────────────────────────────────────────────────────────────────────

    @Test fun runsCoverTheWholeStringAndFlagTheHits() {
        assertEquals(
            listOf((0..0) to true, (1..2) to false, (3..4) to true, (5..5) to false),
            highlightRuns("abcdef", listOf(0, 3, 4)),
        )
        assertEquals(listOf((0..2) to false), highlightRuns("abc", emptyList()))
        assertEquals(listOf((0..2) to true), highlightRuns("abc", listOf(2, 1, 0, 1)))
        assertEquals(emptyList(), highlightRuns("", listOf(0)))
    }

    @Test fun outOfRangeHitsAreIgnored() {
        assertEquals(listOf((0..0) to false, (1..1) to true), highlightRuns("ab", listOf(-1, 1, 2, 99)))
    }

    @Test fun absoluteHitsAreShiftedIntoTheNameAndTheFolder() {
        // "/w/src/Main.kt": /w/ = 0..2, "src" = 3..5, "/" = 6, "Main.kt" = 7..13
        val hit = SearchHit(path = "/w/src/Main.kt", name = "Main.kt", type = "file", score = 1.0, hits = listOf(1, 3, 7, 8, 13))
        val e = toGoToEntry("/w", hit)!!
        assertEquals("src/Main.kt", e.relativePath)
        assertEquals("Main.kt", e.name)
        assertEquals("src", e.folder)
        assertEquals(listOf(0, 1, 6), e.nameHits)
        assertEquals(listOf(0), e.folderHits) // the hit in "/w/" is outside every drawn part
        assertFalse(e.isDir)
    }

    @Test fun topLevelFilesHaveNoFolderAndHitsOutsideTheWorkdirAreDropped() {
        val top = toGoToEntry("/w/", SearchHit("/w/a.kt", "a.kt", "file", 1.0, listOf(3)))!!
        assertEquals("", top.folder)
        assertEquals(listOf(0), top.nameHits)
        assertNull(toGoToEntry("/w", SearchHit("/elsewhere/a.kt", "a.kt", "file", 1.0)))
        assertNull(toGoToEntry("/w", SearchHit("/w", "w", "dir", 1.0)))
        assertTrue(toGoToEntry("/w", SearchHit("/w/src", "src", "dir", 1.0))!!.isDir)
    }

    @Test fun recentEntriesRebuildTheAbsolutePath() {
        val e = recentEntry("/w", "a/b.kt")
        assertEquals("/w/a/b.kt", e.absolutePath)
        assertEquals("b.kt", e.name)
        assertEquals("a", e.folder)
    }

    @Test fun onlyPlainCmdOrCtrlPIsTheChord() {
        assertTrue(isGoToFileChord('P', ctrlOrMeta = true, shift = false, alt = false))
        assertTrue(isGoToFileChord('p', ctrlOrMeta = true, shift = false, alt = false))
        assertFalse(isGoToFileChord('P', ctrlOrMeta = false, shift = false, alt = false))
        assertFalse(isGoToFileChord('P', ctrlOrMeta = true, shift = true, alt = false))
        assertFalse(isGoToFileChord('P', ctrlOrMeta = true, shift = false, alt = true))
        assertFalse(isGoToFileChord('O', ctrlOrMeta = true, shift = false, alt = false))
        assertFalse(isGoToFileChord(null, ctrlOrMeta = true, shift = false, alt = false))
    }

    @Test fun recentsKeepTheLastTenNewestFirstWithoutDuplicates() {
        val v = TreeViewState("/w")
        for (i in 1..12) v.noteOpened("f$i")
        v.noteOpened("f5")
        assertEquals(RecentMax, v.recent.size)
        assertEquals(listOf("f5", "f12", "f11"), v.recent.take(3))
        assertFalse("f1" in v.recent)
    }

    // ── composable ────────────────────────────────────────────────────────────────────────────

    private fun host(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    private fun searchFs(body: String, queries: MutableList<String>) = FileSystemService(
        BrokerApi(
            "http://h", "t",
            HttpClient(
                MockEngine { req ->
                    synchronized(queries) { queries += req.url.parameters["q"].orEmpty() }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        ),
        send = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        graceMs = 0,
    )

    @Test fun typingSearchesAndKeysPickAResult() = runComposeUiTest {
        val queries = mutableListOf<String>()
        val fs = searchFs(
            """[{"path":"/w/src/Main.kt","name":"Main.kt","type":"file","score":2,"hits":[7]},
               {"path":"/w/lib/Mod.kt","name":"Mod.kt","type":"file","score":1,"hits":[7]}]""",
            queries,
        )
        val view = TreeViewState("/w")
        val opened = mutableListOf<GoToEntry>()
        val focus = FocusRequester()
        setContent(
            host {
                FileSearch(fs, view, "/w", onOpen = { opened += it }, focusRequester = focus) {
                    Box(Modifier.size(10.dp).testTag("tree_stub"))
                }
            },
        )
        onNodeWithTag("files_search_field").performTextInput("m")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("files_search_result:lib/Mod.kt").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("files_search_result:src/Main.kt").assertIsDisplayed()
        assertEquals(listOf("m"), synchronized(queries) { queries.toList() })

        onNodeWithTag("files_search_field").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("files_search_field").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("lib/Mod.kt"), opened.map { it.relativePath })
        assertEquals("", view.query)
        onNodeWithTag("files_search_results").assertDoesNotExist()
        onNodeWithTag("tree_stub").assertIsDisplayed()
    }

    @Test fun escClearsAndAnEmptyQueryListsTheRecentFiles() = runComposeUiTest {
        val fs = searchFs("[]", mutableListOf())
        val view = TreeViewState("/w")
        view.noteOpened("a/old.kt")
        view.noteOpened("new.kt")
        val opened = mutableListOf<String>()
        val focus = FocusRequester()
        setContent(host { FileSearch(fs, view, "/w", onOpen = { opened += it.relativePath }, focusRequester = focus) {} })
        waitForIdle()
        onNodeWithTag("files_search_results").assertDoesNotExist() // not focused yet
        runOnIdle { focus.requestFocus() }
        waitForIdle()
        onNodeWithTag("files_search_result:new.kt").assertIsDisplayed()
        onNodeWithTag("files_search_result:a/old.kt").performClick()
        waitForIdle()
        assertEquals(listOf("a/old.kt"), opened)

        runOnIdle { focus.requestFocus() }
        onNodeWithTag("files_search_field").performTextInput("zz")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("files_search_empty").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("files_search_field").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals("", view.query)
    }

    @Test fun cmdOrCtrlPInThePaneFocusesTheField() = runComposeUiTest {
        val view = TreeViewState("/w")
        val focus = FocusRequester()
        val inner = FocusRequester()
        setContent(
            host {
                Box(Modifier.goToFileShortcut { focus.requestFocus() }) {
                    FileSearch(null, view, "/w", onOpen = {}, focusRequester = focus) {
                        Box(Modifier.size(10.dp).focusRequester(inner).focusable().testTag("tree_stub"))
                    }
                }
            },
        )
        runOnIdle { inner.requestFocus() }
        onNodeWithTag("tree_stub").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
        waitForIdle()
        onNodeWithTag("files_search_field").assertIsFocused()
    }
}
