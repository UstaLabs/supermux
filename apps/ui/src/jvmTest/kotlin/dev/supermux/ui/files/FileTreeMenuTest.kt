package dev.supermux.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.net.FsException
import dev.supermux.proto.ServerFrame
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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTreeMenuValidationTest {
    @Test fun acceptsAFreshName() {
        assertNull(validateNewName("x.kt", listOf("a.kt", "src")))
    }

    @Test fun rejectsEmptySlashDotsAndClashes() {
        assertEquals("", validateNewName("", emptyList()))
        assertEquals("", validateNewName("   ", emptyList()))
        assertEquals("A name can't contain “/”", validateNewName("a/b", emptyList()))
        assertEquals("“.” isn't a valid name", validateNewName(".", emptyList()))
        assertEquals("“..” isn't a valid name", validateNewName("..", emptyList()))
        assertEquals("A file with that name already exists", validateNewName("src", listOf("a.kt", "src")))
    }

    @Test fun rejectsLeadingOrTrailingWhitespace() {
        assertEquals("A name can't start or end with a space", validateNewName(" a.kt", emptyList()))
        assertEquals("A name can't start or end with a space", validateNewName("a.kt ", emptyList()))
        assertEquals("A name can't start or end with a space", validateNewName("a.kt\t", emptyList()))
        assertNull(validateNewName("my file.kt", emptyList()))
        // A rename that keeps an existing odd name is not a new mistake.
        assertNull(validateNewName("odd ", listOf("odd "), current = "odd "))
    }

    @Test fun renameMayKeepItsOwnName() {
        assertNull(validateNewName("a.kt", listOf("a.kt", "b.kt"), current = "a.kt"))
        assertEquals("A file with that name already exists", validateNewName("b.kt", listOf("a.kt", "b.kt"), current = "a.kt"))
    }

    @Test fun errorMessagesFromTheBroker() {
        assertEquals("A file with that name already exists", fsOpErrorMessage(FsException(409, """{"error":"EEXIST","message":"exists"}""")))
        assertEquals("nope", fsOpErrorMessage(FsException(400, """{"error":"EINVAL","message":"nope"}""")))
        assertEquals("Permission denied", fsOpErrorMessage(FsException(403, """{"error":"EACCES","message":"x"}""")))
        assertEquals("boom", fsOpErrorMessage(RuntimeException("boom")))
        // EXDEV is a 409 too, but not a name clash.
        assertEquals(
            "Can't move to the trash across filesystems; delete permanently instead?",
            fsOpErrorMessage(FsException(409, """{"error":"EXDEV","message":"Can't move to the trash across filesystems; delete permanently instead?"}""")),
        )
    }

    @Test fun newEntriesGoInsideFoldersAndBesideFiles() {
        val folder = TreeRow(path = "/w/src", depth = 0, entry = FsEntry(name = "src", type = "dir"), status = RowStatus.CLOSED)
        val file = TreeRow(path = "/w/src/a.kt", depth = 1, entry = FsEntry(name = "a.kt", type = "file"), status = RowStatus.FILE)
        assertEquals("/w/src", newEntryParent(folder))
        assertEquals("/w/src", newEntryParent(file))
    }
}

@OptIn(ExperimentalTestApi::class)
class FileTreeMenuTest {
    private fun host(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    /** A Files service over a mock broker: `/fs/ops` bodies land in [bodies]; [gate] holds each op's reply. */
    private class Fs(val gate: CompletableDeferred<Unit>? = null) {
        val bodies = mutableListOf<String>()
        val service = FileSystemService(
            BrokerApi(
                "http://h", "t",
                HttpClient(
                    MockEngine { req ->
                        if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/fs/ops")) {
                            val text = String(req.body.toByteArray())
                            synchronized(bodies) { bodies += text }
                            gate?.await()
                        }
                        respond("{}")
                    },
                ),
            ),
            send = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 0,
        )
        fun ops(): List<JsonObject> = synchronized(bodies) { bodies.map { Json.parseToJsonElement(it) as JsonObject } }
    }

    private fun Fs.list(vararg entries: FsEntry) {
        service.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = entries.toList()))
    }

    @Test fun longPressNewFolderPostsMkdir() = runComposeUiTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/fs/ops")) {
                val text = String(req.body.toByteArray())
                synchronized(bodies) { bodies += text }
            }
            respond("{}")
        }
        val fs = FileSystemService(
            BrokerApi("http://h", "t", HttpClient(engine)),
            send = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            graceMs = 0,
        )
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs, view, onOpenFile = {}) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"), FsEntry(name = "a.kt", type = "file"))))
        waitForIdle()

        onNodeWithTag("tree_row:src").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_new_folder").performClick()
        waitForIdle()
        // Nothing typed yet → can't create.
        onNodeWithTag("tree_dialog_confirm").assertIsNotEnabled()
        onNodeWithTag("tree_dialog_name").performTextInput("x")
        waitForIdle()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { synchronized(bodies) { bodies.isNotEmpty() } }

        val body = Json.parseToJsonElement(synchronized(bodies) { bodies.single() }) as JsonObject
        assertEquals(JsonPrimitive("mkdir"), body["op"])
        assertEquals(JsonPrimitive("/w/src/x"), body["path"])
        waitUntil(timeoutMillis = 5_000) { view.selected == "/w/src/x" }
        assertEquals(true, "/w/src" in view.expanded)
    }

    @Test fun renameReportsTheMoveToTheHost() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        val moves = mutableListOf<Pair<String, String?>>()
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}, onEntryMoved = { o, n -> moves += o to n }) })
        waitForIdle()
        fs.list(FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_rename").performClick()
        waitForIdle()
        onNodeWithTag("tree_dialog_name").performTextReplacement("b.kt")
        waitForIdle()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { moves.isNotEmpty() }

        assertEquals(listOf<Pair<String, String?>>("/w/a.kt" to "/w/b.kt"), moves)
        assertEquals(JsonPrimitive("rename"), fs.ops().single()["op"])
    }

    @Test fun deleteReportsTheMoveAsGone() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        val moves = mutableListOf<Pair<String, String?>>()
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}, onEntryMoved = { o, n -> moves += o to n }) })
        waitForIdle()
        fs.list(FsEntry(name = "src", type = "dir"))
        waitForIdle()

        onNodeWithTag("tree_row:src").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_delete").performClick()
        waitForIdle()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { moves.isNotEmpty() }

        assertEquals(listOf<Pair<String, String?>>("/w/src" to null), moves)
    }

    @Test fun aTrashMoveAcrossFilesystemsOffersAPermanentDelete() = runComposeUiTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/fs/ops")) {
                val text = String(req.body.toByteArray())
                synchronized(bodies) { bodies += text }
                if ("permanent" !in text) {
                    return@MockEngine respond(
                        """{"error":"EXDEV","message":"Can't move to the trash across filesystems; delete permanently instead?"}""",
                        io.ktor.http.HttpStatusCode.Conflict,
                    )
                }
            }
            respond("{}")
        }
        val fs = FileSystemService(BrokerApi("http://h", "t", HttpClient(engine)), send = {}, scope = CoroutineScope(Dispatchers.Unconfined), graceMs = 0)
        val view = TreeViewState("/w")
        val moves = mutableListOf<Pair<String, String?>>()
        setContent(host { FileTreeWithActions(fs, view, onOpenFile = {}, onEntryMoved = { o, n -> moves += o to n }) })
        waitForIdle()
        fs.onFrame(ServerFrame.FsDir(path = "/w", version = "1", entries = listOf(FsEntry(name = "src", type = "dir"))))
        waitForIdle()

        onNodeWithTag("tree_row:src").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_delete").performClick()
        waitForIdle()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("tree_dialog_delete_permanently").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Delete permanently? This can't be undone.", substring = true).assertExists()
        assertEquals(emptyList<Pair<String, String?>>(), moves) // nothing deleted yet
        onNodeWithTag("tree_dialog_delete_permanently").performClick()
        waitUntil(timeoutMillis = 5_000) { moves.isNotEmpty() }

        val sentOps = synchronized(bodies) { bodies.map { Json.parseToJsonElement(it) as JsonObject } }
        assertEquals(2, sentOps.size)
        assertNull(sentOps[0]["permanent"])
        assertEquals(JsonPrimitive(true), sentOps[1]["permanent"])
        assertEquals(listOf<Pair<String, String?>>("/w/src" to null), moves)
    }

    @Test fun cancelIsDisabledWhileTheOpIsInFlight() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val fs = Fs(gate)
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list(FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_delete").performClick()
        waitForIdle()
        onNodeWithTag("tree_dialog_cancel").assertIsEnabled()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { fs.ops().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("tree_dialog_cancel").assertIsNotEnabled()
        onNodeWithTag("tree_dialog_cancel").performClick() // ignored
        waitForIdle()
        assertNotNull(view.dialog)

        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { view.dialog == null }
    }

    @Test fun rightClickOpensTheMenuWithoutOpeningTheRow() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        val opened = mutableListOf<String>()
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = { opened += it }) })
        waitForIdle()
        fs.list(FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performMouseInput { rightClick(center) }
        waitForIdle()

        onNodeWithTag("tree_menu_rename").assertExists()
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun theMenuClosesForGoodWhenItsRowScrollsOutOfView() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { Box(Modifier.height(240.dp)) { FileTreeWithActions(fs.service, view, onOpenFile = {}) } })
        waitForIdle()
        fs.list(*Array(80) { FsEntry(name = "f%02d.kt".format(it), type = "file") })
        waitForIdle()

        onNodeWithTag("tree_row:f00.kt").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("tree_menu_rename").assertExists()

        runOnIdle { runBlocking { view.list.scrollToItem(60) } }
        waitForIdle()
        onNodeWithTag("tree_menu_rename").assertDoesNotExist()

        runOnIdle { runBlocking { view.list.scrollToItem(0) } }
        waitForIdle()
        onNodeWithTag("tree_row:f00.kt").assertExists()
        onNodeWithTag("tree_menu_rename").assertDoesNotExist()
    }

    @Test fun aDialogSetOnTheViewCreatesAtTheRoot() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list(FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        view.dialog = FileTreeDialog.NewEntry("/w", folder = false)
        waitForIdle()
        onNodeWithTag("tree_dialog_name").performTextInput("n.kt")
        waitForIdle()
        onNodeWithTag("tree_dialog_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { fs.ops().isNotEmpty() }

        val op = fs.ops().single()
        assertEquals(JsonPrimitive("touch"), op["op"])
        assertEquals(JsonPrimitive("/w/n.kt"), op["path"])
    }
}
