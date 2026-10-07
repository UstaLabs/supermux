package dev.supermux.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import dev.supermux.fs.FileSystemService
import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the in-place edit: temporary-row placement, selection, blur, and the model. */
class InlineEditModelTest {
    private fun dir(path: String, depth: Int, status: RowStatus = RowStatus.CLOSED) =
        TreeRow(path, depth, FsEntry(name = displayName(path), type = "dir"), status)
    private fun file(path: String, depth: Int) =
        TreeRow(path, depth, FsEntry(name = displayName(path), type = "file"), RowStatus.FILE)

    // /w: lib/ (open: deep/, z.kt), src/, a.kt, b.kt
    private val rows = listOf(
        dir("/w/lib", 0, RowStatus.OPEN),
        dir("/w/lib/deep", 1),
        file("/w/lib/z.kt", 1),
        dir("/w/src", 0),
        file("/w/a.kt", 0),
        file("/w/b.kt", 0),
    )

    @Test fun aNewFolderGoesAtTheTopOfItsFolder() {
        assertEquals(InlineSlot(0, 0), inlineCreateSlot(rows, "/w", InlineEdit.Create("/w", folder = true)))
        assertEquals(InlineSlot(1, 1), inlineCreateSlot(rows, "/w", InlineEdit.Create("/w/lib", folder = true)))
    }

    @Test fun aNewFileGoesAfterTheFoldersAndWhatIsOpenUnderThem() {
        assertEquals(InlineSlot(4, 0), inlineCreateSlot(rows, "/w", InlineEdit.Create("/w", folder = false)))
        assertEquals(InlineSlot(2, 1), inlineCreateSlot(rows, "/w", InlineEdit.Create("/w/lib", folder = false)))
    }

    @Test fun aFolderWithNoRowsYetGetsTheSlotRightUnderIt() {
        // src is closed/loading: nothing listed under it yet.
        assertEquals(InlineSlot(4, 1), inlineCreateSlot(rows, "/w", InlineEdit.Create("/w/src", folder = false)))
        // A folder of only files: a new file goes after the (no) folders, i.e. first.
        val onlyFiles = listOf(dir("/w/lib", 0, RowStatus.OPEN), file("/w/lib/z.kt", 1))
        assertEquals(InlineSlot(1, 1), inlineCreateSlot(onlyFiles, "/w", InlineEdit.Create("/w/lib", folder = false)))
    }

    @Test fun anEmptyTreeOrAFolderAtTheEnd() {
        assertEquals(InlineSlot(0, 0), inlineCreateSlot(emptyList(), "/w", InlineEdit.Create("/w", folder = false)))
        val onlyDirs = listOf(dir("/w/a", 0), dir("/w/b", 0))
        assertEquals(InlineSlot(2, 0), inlineCreateSlot(onlyDirs, "/w", InlineEdit.Create("/w", folder = false)))
        assertEquals(InlineSlot(2, 1), inlineCreateSlot(onlyDirs, "/w", InlineEdit.Create("/w/b", folder = false)))
    }

    @Test fun aParentNotOnScreenHasNoSlot() {
        assertNull(inlineCreateSlot(rows, "/w", InlineEdit.Create("/w/src/ui", folder = false)))
        assertNull(inlineCreateSlot(rows, "/w", InlineEdit.Create("/elsewhere", folder = false)))
        assertNull(inlineCreateSlot(rows, "/w", InlineEdit.Create("/w/a.kt", folder = false)))
    }

    @Test fun aRenameSelectsTheStemOfAFileAndAllOfAFolder() {
        assertEquals(4, renameSelectionEnd("main.kt", folder = false))
        assertEquals(7, renameSelectionEnd("app.tar.gz", folder = false)) // the LAST extension
        assertEquals(4, renameSelectionEnd(".env", folder = false))
        assertEquals(8, renameSelectionEnd("Makefile", folder = false))
        assertEquals(6, renameSelectionEnd("lib.rs", folder = true))
    }

    @Test fun aBlurCommitsOnlyAValidChangedName() {
        assertTrue(commitsOnBlur("x.kt", null, current = null))
        assertTrue(commitsOnBlur("b.kt", null, current = "a.kt"))
        assertEquals(false, commitsOnBlur("", "", current = null))
        assertEquals(false, commitsOnBlur("a/b", "A name can't contain “/”", current = null))
        assertEquals(false, commitsOnBlur("a.kt", null, current = "a.kt"))
    }

    @Test fun startActionGoesInPlaceOnlyWithAKeyboard() {
        val v = TreeViewState("/w")
        v.startAction(FileTreeDialog.NewEntry("/w/src/ui", folder = false), inline = true)
        assertEquals(InlineEdit.Create("/w/src/ui", folder = false), v.inlineEdit)
        assertNull(v.dialog)
        assertEquals(setOf("/w/src", "/w/src/ui"), v.expanded) // its folders open for the row

        v.startAction(FileTreeDialog.Rename("/w/a.kt"), inline = true)
        assertEquals(InlineEdit.Rename("/w/a.kt"), v.inlineEdit)

        // Delete always confirms, and drops an open edit.
        v.startAction(FileTreeDialog.Delete("/w/a.kt", folder = false), inline = true)
        assertIs<FileTreeDialog.Delete>(v.dialog)
        assertNull(v.inlineEdit)

        val touch = TreeViewState("/w")
        touch.startAction(FileTreeDialog.Rename("/w/a.kt"), inline = false)
        assertEquals(FileTreeDialog.Rename("/w/a.kt"), touch.dialog)
        assertNull(touch.inlineEdit)
    }
}

@OptIn(ExperimentalTestApi::class)
class FileTreeInlineEditTest {
    private fun host(keyboard: Boolean = true, content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiPrefs provides UiPrefs(InMemorySettingsStore()),
            LocalPlatform provides FakePlatform(),
            LocalHardwareKeyboard provides keyboard,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { content() }
        }
    }

    /** A Files service over a mock broker: `/fs/ops` bodies land in [ops]; [reply] answers each. */
    private class Fs(val reply: (String) -> Pair<String, HttpStatusCode> = { "{}" to HttpStatusCode.OK }) {
        private val bodies = mutableListOf<String>()
        val service = FileSystemService(
            BrokerApi(
                "http://h", "t",
                HttpClient(
                    MockEngine { req ->
                        if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/fs/ops")) {
                            val text = String(req.body.toByteArray())
                            synchronized(bodies) { bodies += text }
                            val (body, status) = reply(text)
                            return@MockEngine respond(body, status)
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
        fun list(path: String, vararg entries: FsEntry, version: String = "1") {
            service.onFrame(ServerFrame.FsDir(path = path, version = version, entries = entries.toList()))
        }
    }

    @Test fun f2RenamesInPlace() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        val moves = mutableListOf<Pair<String, String?>>()
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}, onEntryMoved = { o, n -> moves += o to n }) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "main.kt", type = "file"), FsEntry(name = "z.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:main.kt").performClick()
        waitForIdle()
        onNodeWithTag("editor_tree").performKeyInput { pressKey(Key.F2) }
        waitForIdle()

        val field = onNodeWithTag("tree_inline_field")
        field.assertIsFocused()
        // The stem is selected, so typing keeps ".kt".
        assertEquals(TextRange(0, 4), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInput("util")
        waitForIdle()
        // Typing must not have driven the tree's type-ahead / keyboard nav.
        assertEquals("/w/main.kt", view.selected)
        field.performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { moves.isNotEmpty() }

        val op = fs.ops().single()
        assertEquals(JsonPrimitive("rename"), op["op"])
        assertEquals(JsonPrimitive("/w/main.kt"), op["path"])
        assertEquals(JsonPrimitive("/w/util.kt"), op["to"])
        assertEquals(listOf<Pair<String, String?>>("/w/main.kt" to "/w/util.kt"), moves)
        waitForIdle()
        onNodeWithTag("tree_inline_field").assertDoesNotExist()
        assertNull(view.inlineEdit)
        assertEquals("/w/util.kt", view.selected)
        onNodeWithTag("editor_tree").assertIsFocused()

        // The broker's listing follows; the row shows the new name.
        fs.list("/w", FsEntry(name = "util.kt", type = "file"), FsEntry(name = "z.kt", type = "file"), version = "2")
        waitForIdle()
        onNodeWithTag("tree_row:util.kt").assertExists()
        onNodeWithTag("tree_row:main.kt").assertDoesNotExist()
    }

    @Test fun escCancelsARename() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performClick()
        waitForIdle()
        onNodeWithTag("editor_tree").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        onNodeWithTag("tree_inline_field").performTextInput("b")
        waitForIdle()
        onNodeWithTag("tree_inline_field").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithTag("tree_inline_field").assertDoesNotExist()
        onNodeWithTag("tree_row:a.kt").assertExists()
        assertNull(view.inlineEdit)
        assertEquals("/w/a.kt", view.selected)
        onNodeWithTag("editor_tree").assertIsFocused()
        assertEquals(emptyList(), fs.ops())
    }

    @Test fun theMenuRenamesInPlaceToo() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "src", type = "dir"))
        waitForIdle()

        onNodeWithTag("tree_row:src").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("tree_menu_rename").performClick()
        waitForIdle()
        onAllNodesWithTag("tree_dialog_name").assertCountEquals0()
        val field = onNodeWithTag("tree_inline_field")
        // A folder's whole name is selected.
        assertEquals(TextRange(0, 3), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInput("lib")
        field.performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { fs.ops().isNotEmpty() }
        assertEquals(JsonPrimitive("/w/lib"), fs.ops().single()["to"])
    }

    @Test fun newFileFromTheMenuInsertsATemporaryRowAfterTheFolders() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        val opened = mutableListOf<String>()
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = { opened += it }) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "src", type = "dir"), FsEntry(name = "top.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:src").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("tree_menu_new_file").performClick()
        waitForIdle()
        // Its folder opened for it.
        assertTrue("/w/src" in view.expanded)
        fs.list("/w/src", FsEntry(name = "lib", type = "dir"), FsEntry(name = "x.kt", type = "file"))
        waitForIdle()

        val temp = onNodeWithTag("tree_new_entry").getUnclippedBoundsInRoot()
        val lib = onNodeWithTag("tree_row:lib").getUnclippedBoundsInRoot()
        val x = onNodeWithTag("tree_row:x.kt").getUnclippedBoundsInRoot()
        assertTrue(temp.top >= lib.bottom && temp.bottom <= x.top, "temp row between lib/ and x.kt: $lib $temp $x")
        // Indented like its siblings.
        assertEquals(lib.left, temp.left)

        val field = onNodeWithTag("tree_inline_field")
        field.assertIsFocused()
        field.performTextInput("n.kt")
        field.performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { opened.isNotEmpty() }

        val op = fs.ops().single()
        assertEquals(JsonPrimitive("touch"), op["op"])
        assertEquals(JsonPrimitive("/w/src/n.kt"), op["path"])
        assertEquals(listOf("/w/src/n.kt"), opened)
        assertEquals("/w/src/n.kt", view.selected)
        waitForIdle()
        onNodeWithTag("tree_new_entry").assertDoesNotExist()
    }

    @Test fun aNewFolderAtTheRootGoesFirstAndPostsMkdir() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "src", type = "dir"), FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        // What the pane header's ⋮ → New folder… does.
        runOnIdle { view.startAction(FileTreeDialog.NewEntry("/w", folder = true), inline = true) }
        waitForIdle()
        val temp = onNodeWithTag("tree_new_entry").getUnclippedBoundsInRoot()
        val src = onNodeWithTag("tree_row:src").getUnclippedBoundsInRoot()
        assertTrue(temp.bottom <= src.top, "temp row above src/: $temp $src")

        onNodeWithTag("tree_inline_field").performTextInput("docs")
        onNodeWithTag("tree_inline_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { fs.ops().isNotEmpty() }
        assertEquals(JsonPrimitive("mkdir"), fs.ops().single()["op"])
        assertEquals(JsonPrimitive("/w/docs"), fs.ops().single()["path"])
        waitUntil(timeoutMillis = 5_000) { view.selected == "/w/docs" }
    }

    @Test fun blurWithAnEmptyNameCancels() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithTag("tree_menu_new_file").performClick()
        waitForIdle()
        onNodeWithTag("tree_inline_field").assertIsFocused()
        // Focus moves elsewhere (a click on another row gives the tree focus).
        onNodeWithTag("tree_row:a.kt").performClick()
        waitForIdle()

        onNodeWithTag("tree_new_entry").assertDoesNotExist()
        assertNull(view.inlineEdit)
        assertEquals(emptyList(), fs.ops())
    }

    @Test fun blurWithAValidNameCreates() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        runOnIdle { view.startAction(FileTreeDialog.NewEntry("/w", folder = false), inline = true) }
        waitForIdle()
        onNodeWithTag("tree_inline_field").performTextInput("b.kt")
        waitForIdle()
        onNodeWithTag("tree_row:a.kt").performClick()
        waitUntil(timeoutMillis = 5_000) { fs.ops().isNotEmpty() }
        assertEquals(JsonPrimitive("/w/b.kt"), fs.ops().single()["path"])
    }

    @Test fun anInvalidNameShowsInlineAndPostsNothing() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        runOnIdle { view.startAction(FileTreeDialog.NewEntry("/w", folder = false), inline = true) }
        waitForIdle()
        onNodeWithTag("tree_inline_field").performTextInput("a.kt")
        waitForIdle()
        onNodeWithTag("tree_inline_error").assertExists()
        onNodeWithTag("tree_inline_field").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        // Still open to fix, nothing sent.
        onNodeWithTag("tree_inline_field").assertIsFocused()
        assertEquals(emptyList(), fs.ops())
    }

    @Test fun aBrokerRefusalShowsInlineAndKeepsTheFieldOpen() = runComposeUiTest {
        val fs = Fs { """{"error":"EACCES","message":"nope"}""" to HttpStatusCode.Forbidden }
        val view = TreeViewState("/w")
        setContent(host { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        runOnIdle { view.startAction(FileTreeDialog.NewEntry("/w", folder = false), inline = true) }
        waitForIdle()
        onNodeWithTag("tree_inline_field").performTextInput("b.kt")
        onNodeWithTag("tree_inline_field").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("tree_inline_error").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("tree_inline_field").assertExists()
        assertIs<InlineEdit.Create>(view.inlineEdit)
    }

    @Test fun withoutAKeyboardTheDialogStillOpens() = runComposeUiTest {
        val fs = Fs()
        val view = TreeViewState("/w")
        setContent(host(keyboard = false) { FileTreeWithActions(fs.service, view, onOpenFile = {}) })
        waitForIdle()
        fs.list("/w", FsEntry(name = "a.kt", type = "file"))
        waitForIdle()

        onNodeWithTag("tree_row:a.kt").performClick()
        waitForIdle()
        onNodeWithTag("editor_tree").performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        onNodeWithTag("tree_dialog_name").assertExists()
        onNodeWithTag("tree_inline_field").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
    assertEquals(0, fetchSemanticsNodes().size)
}
