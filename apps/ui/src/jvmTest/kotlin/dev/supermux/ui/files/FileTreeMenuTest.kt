package dev.supermux.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
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

    @Test fun renameMayKeepItsOwnName() {
        assertNull(validateNewName("a.kt", listOf("a.kt", "b.kt"), current = "a.kt"))
        assertEquals("A file with that name already exists", validateNewName("b.kt", listOf("a.kt", "b.kt"), current = "a.kt"))
    }

    @Test fun errorMessagesFromTheBroker() {
        assertEquals("A file with that name already exists", fsOpErrorMessage(FsException(409, """{"error":"EEXIST","message":"exists"}""")))
        assertEquals("nope", fsOpErrorMessage(FsException(400, """{"error":"EINVAL","message":"nope"}""")))
        assertEquals("Permission denied", fsOpErrorMessage(FsException(403, """{"error":"EACCES","message":"x"}""")))
        assertEquals("boom", fsOpErrorMessage(RuntimeException("boom")))
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
}
