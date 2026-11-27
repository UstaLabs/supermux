package dev.supermux.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.BrokerApi
import dev.supermux.state.SourceControlApi
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.FakeSettingsStore
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #72 phase one + v5, clicked through as a person would on the desktop: the pane against a fake host that answers
 * like the broker and records what the app sends.
 */
@OptIn(ExperimentalTestApi::class)
class SourceControlUiTest {
    private val withFiles = """[{"path":"src/App.jsx","status":"modified","staged":"none","baseBlob":"a"},{"path":"src/debug.js","status":"added","staged":"none"}]"""
    private val conflict = """[{"path":"src/App.jsx","status":"modified","staged":"conflict","baseBlob":"a"}]"""
    private val chatHistory = """{"branch":"feature/search","base":"dev","chat":[{"sha":"c41e9d2aaaa","subject":"Add a search box","author":"ada","at":1,"pushed":true}],"baseCommits":[{"sha":"9f3c2a1bbbb","subject":"Cart","author":"grace","at":1}],"incoming":%d}"""

    /** The host: answers per path (and per call for POST …/git/pr), records "METHOD path body". */
    private class Host(
        val status: String = """{"isRepo":true,"hasRemote":true,"remotes":["origin"],"branch":"feature/search","upstream":"origin/feature/search","ahead":0,"behind":0}""",
        val files: String = "[]",
        val merging: Boolean = false,
        val incoming: Int = 0,
        val prCreate: List<Pair<HttpStatusCode, String>> = emptyList(),
        /** POST …/git/pull's answer. */
        val pull: String = """{"status":"clean"}""",
    ) {
        /** Written on MockEngine's threads, read on the test's. */
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        private var creates = 0
        fun api(history: String): SourceControlApi {
            val engine = MockEngine { req ->
                val p = req.url.encodedPath
                val body = (req.body as? io.ktor.http.content.TextContent)?.let { String(it.bytes()) }.orEmpty()
                if (req.method.value != "GET") sent += "${req.method.value} $p $body"
                val (code, b) = when {
                    p.endsWith("/changes") -> HttpStatusCode.OK to """{"repos":[{"repo":"","baseSha":"h","files":$files,"merging":$merging}]}"""
                    p.endsWith("/git/status") -> HttpStatusCode.OK to status
                    // As the broker: only the chat's (session) route knows its base; the workspace's has none.
                    p.endsWith("/git/history") && p.startsWith("/sessions/") -> HttpStatusCode.OK to history.format(incoming)
                    p.endsWith("/git/history") -> HttpStatusCode.OK to history.format(incoming).replace("\"base\":\"dev\"", "\"base\":null")
                    p.endsWith("/git/pr/base") -> HttpStatusCode.OK to """{"target":"dev","onRemote":true,"ahead":1}"""
                    p.endsWith("/git/pr/text") -> HttpStatusCode.OK to """{"title":"Add a search box","body":"- a box above the grid","by":"agent"}"""
                    p.endsWith("/git/pr") && req.method.value == "POST" -> prCreate.getOrElse(creates++) { HttpStatusCode.OK to """{"status":"opened","url":"https://github.com/acme/shop/pull/7","number":7}""" }
                    p.endsWith("/git/pr") -> HttpStatusCode.OK to """{"status":"none","forge":"github"}"""
                    p.endsWith("/git/branches") -> HttpStatusCode.OK to """{"current":"feature/search","local":[{"name":"feature/search","openIn":{"kind":"this"}},{"name":"dev","isDefault":true}],"remote":[{"name":"origin/dev"},{"name":"origin/release"}]}"""
                    p.endsWith("/git/switch") -> HttpStatusCode.OK to """{"status":"switched","branch":"fix/typo"}"""
                    p.endsWith("/git/latest") -> HttpStatusCode.OK to """{"status":"clean","commits":2,"from":"dev","before":"aaaaaaa","after":"bbbbbbb"}"""
                    p.endsWith("/git/pull") -> HttpStatusCode.OK to pull
                    p.endsWith("/git/publish") || p.endsWith("/git/push") -> HttpStatusCode.OK to """{"status":"pushed"}"""
                    p.endsWith("/git/resolve") -> HttpStatusCode.OK to """{"ok":true}"""
                    else -> HttpStatusCode.OK to "{}"
                }
                respond(ByteReadChannel(b), code, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            return SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1")
        }
    }

    /**
     * The pane's coroutines on a standard test dispatcher. The default one is unconfined: a coroutine resumes on the
     * thread that woke it (MockEngine's IO threads), so the pane's state was written off the UI thread while a frame was
     * measured (#166: an IndexOutOfBoundsException in the file list).
     */
    private fun scmTest(block: suspend ComposeUiTest.() -> Unit) =
        runComposeUiTest(effectContext = StandardTestDispatcher(), block = block)

    private fun ComposeUiTest.pane(host: Host): SourceControlState {
        val state = SourceControlState(host.api(chatHistory))
        setContent {
            PlatformTestProviders(FakePlatform(), pointer = true, widthClass = WindowWidthClass.Expanded, inputMode = InputMode.Pointer, uiPrefs = UiPrefs(FakeSettingsStore())) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.size(420.dp, 1400.dp)) { SourceControlPane(state, onOpenDiff = { _, _, _, _ -> }, onOpenFile = { _, _ -> }, onReview = {}) }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { state.repos != null && state.status != null && state.history != null }
        waitForIdle()
        return state
    }

    /** Click once the node is there and enabled (a check or a load may still be running). */
    private fun ComposeUiTest.click(tag: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.sent(host: Host, what: String) {
        waitUntil(timeoutMillis = 5_000) { host.sent.any { it.contains(what) } }
    }

    @Test fun publishWithSeveralRemotesAsksWhichAndPublishesThere() = scmTest {
        val host = Host(status = """{"isRepo":true,"hasRemote":true,"remotes":["fork","origin"],"branch":"feature/search","upstream":null}""")
        pane(host)
        click("scm_where_sync")                                        // "not published" on the where line
        onNodeWithTag("scm_remote_pick").assertExists()
        click("scm_remote_fork")
        sent(host, """/git/publish {"remote":"fork"}""")
    }

    @Test fun createPullRequestAsksAboutUnpushedCommitsThenPushes() = scmTest {
        val host = Host(prCreate = listOf(HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":2}"""))
        pane(host)
        click("scm_pr_create")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("scm_pr_title_input")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("scm_pr_title_input") and hasText("Add a search box")).fetchSemanticsNodes().isNotEmpty() } // the agent's title, written for dev
        click("scm_pr_create_go")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("You have 2 unpushed commits on feature/search.")).fetchSemanticsNodes().isNotEmpty() }
        click("scm_pr_ask_confirm")                                    // Push Commits
        sent(host, "\"push\":true")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("scm_pr_form")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun alwaysPushIsRememberedOnTheHost() = scmTest {
        val host = Host(prCreate = listOf(HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":1}"""))
        pane(host)
        click("scm_pr_create")
        click("scm_pr_create_go")
        click("scm_pr_ask_extra_0")                                    // Always Push
        sent(host, "\"gitPrPush\":\"always\"")
        sent(host, "\"push\":true")
    }

    @Test fun continueWithoutPushingCreatesFromWhatIsOnTheRemote() = scmTest {
        val host = Host(prCreate = listOf(HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":1}"""))
        pane(host)
        click("scm_pr_create")
        click("scm_pr_create_go")
        click("scm_pr_ask_extra_1")                                    // Continue Without Pushing
        sent(host, "\"skipPush\":true")
    }

    @Test fun aFailedCreateSaysWhyByTheButtonWithGhsWordsUnderDetails() = scmTest {
        val gh = "pull request create failed: GraphQL: Something unexpected happened (createPullRequest)"
        val host = Host(prCreate = listOf(HttpStatusCode.Conflict to """{"status":"unavailable","forge":"github","reason":"error","message":"$gh"}"""))
        pane(host)
        click("scm_pr_create")
        click("scm_pr_create_go")
        onNodeWithText("Couldn't open the pull request.").assertExists()
        click("scm_pr_problem_details")
        onNodeWithText(gh).assertExists()
    }

    @Test fun theBranchPickerCreatesTheTypedBranch() = scmTest {
        val host = Host()
        pane(host)
        click("scm_where_branch")
        onNodeWithTag("scm_picker_filter").performTextInput("fix/typo")
        click("scm_pick_create")
        sent(host, "/git/switch")
        assertTrue(host.sent.last { it.contains("/git/switch") }.contains("fix/typo"))
    }

    @Test fun aConflictIsSettledFromItsRow() = scmTest {
        val host = Host(files = conflict, merging = true)
        pane(host)
        onNodeWithTag("scm_merging").assertExists()
        onNodeWithTag("scm_row_merge_src/App.jsx").performMouseInput { moveTo(center) }   // the links show on hover, as in VS Code
        click("scm_accept_current_src/App.jsx")
        sent(host, "\"side\":\"mine\"")
    }

    @Test fun theBaseMovedOnGetLatestFromTheBaseLineAndTheGraph() = scmTest {
        val host = Host(incoming = 5)
        pane(host)
        onNodeWithText("dev has 5 new", substring = true).assertExists()
        click("scm_base_get_latest")
        sent(host, "/sessions/s1/git/latest")
        host.sent.clear()
        click("scm_graph_incoming_base")
        sent(host, "/sessions/s1/git/latest")
    }

    @Test fun theWhereLineIsTheUpstreamAndNeverTheBase() = scmTest {
        val host = Host(status = """{"isRepo":true,"hasRemote":true,"remotes":["origin"],"branch":"feature/search","upstream":"origin/feature/search","ahead":1,"behind":2}""", incoming = 3)
        pane(host)
        onNodeWithText("2↓ 1↑ vs origin/feature/search").assertExists()
        onNodeWithText("dev has 3 new", substring = true).assertExists()
        click("scm_where_sync")                                        // syncs: pull then push, against origin
        sent(host, "/git/pull")
        sent(host, "/git/push")
    }

    /** Found clicking through: Sync pushed after its pull stopped on a conflict. VS Code's Sync stops there. */
    @Test fun syncStopsAtAPullConflictAndSaysSo() = scmTest {
        val host = Host(status = """{"isRepo":true,"hasRemote":true,"remotes":["origin"],"branch":"feature/search","upstream":"origin/feature/search","ahead":1,"behind":1}""",
            pull = """{"status":"conflict","files":["notes.md"]}""")
        pane(host)
        click("scm_commit")                                            // the main button: Sync Changes 1↓ 1↑
        sent(host, "/git/pull")
        onNodeWithText("There are merge conflicts. Resolve them, then Continue.").assertExists()
        assertTrue(host.sent.none { it.contains("/git/push") }, host.sent.joinToString())
    }

    /** VS Code: nothing to commit, the button is off. */
    @Test fun commitIsOffWithNothingToCommit() = scmTest {
        pane(Host())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("scm_commit")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(hasTestTag("scm_commit") and isEnabled()).fetchSemanticsNodes().isEmpty())
    }

    /** Found clicking through: the history came from the workspace, which has no base, so the base line never showed. */
    @Test fun theHistoryIsTheChatsOwnSoItsBaseShows() = scmTest {
        val host = Host()
        val state = pane(host)
        assertTrue(state.base == "dev")
        onNodeWithTag("scm_base_line").assertExists()
    }
}
