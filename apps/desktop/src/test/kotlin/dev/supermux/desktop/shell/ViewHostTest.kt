package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.net.BrokerApi
import dev.supermux.proto.ViewDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

private fun view(kind: String, state: Map<String, String>) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

/**
 * ViewHost on disk takes [DesktopAppState] + drafts (Phase 3). The plan's simplified
 * signature is adapted here — follow the disk.
 *
 * Workspace terminals inject a pure-Compose stand-in: SwingPanel/JediTerm cannot
 * be hosted under runComposeUiTest (same pattern as SessionDetailTest's nativePanelContent).
 */
@OptIn(ExperimentalTestApi::class)
class ViewHostTest {

    private fun fakeApp(): DesktopAppState {
        val engine = MockEngine { req ->
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default),
            connectOnInit = false,
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test
    fun aWorkspaceTerminalBuildsTheTerminalWidget() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("terminal", mapOf("scope" to "workspace", "terminalId" to "main")),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
                workspaceTerminalContent = { _, mod ->
                    Box(mod.fillMaxSize()) { Text("term-stand-in") }
                },
            )
        }
        onNodeWithTag("terminal-w1-main").assertIsDisplayed()
    }

    @Test
    fun anUnknownKindDrawsAHintRatherThanCrashing() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("hologram", emptyMap()),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun anEditorViewGetsTheWorkspaceWorkdir() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("editor", mapOf("mode" to "tree")),
                workspaceId = "w1",
                workdir = "/some/dir",
                app = app,
                drafts = mutableStateMapOf(),
            )
        }
        onNodeWithTag("editor-/some/dir").assertIsDisplayed()
        // Chatless workspace: quiet note that code intelligence is off.
        onNodeWithTag("editor-no-lsp").assertIsDisplayed()
    }

    @Test
    fun aChatViewWithNoSessionIdDrawsTheHint() = runComposeUiTest {
        val app = fakeApp()
        setContent {
            ViewHost(
                view = view("chat", emptyMap()),
                workspaceId = "w1",
                workdir = "/w",
                app = app,
                drafts = mutableStateMapOf(),
            )
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }
}
