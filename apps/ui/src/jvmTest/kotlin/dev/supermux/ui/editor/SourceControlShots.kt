package dev.supermux.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.BrokerApi
import dev.supermux.state.SourceControlApi
import dev.supermux.ui.chat.PlatformTestProviders
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.FakeSettingsStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

private val f3 = """[{"path":"src/App.jsx","status":"modified","staged":"none","baseBlob":"a"},{"path":"src/styles.css","status":"modified","staged":"none","baseBlob":"b"},{"path":"src/debug.js","status":"added","staged":"none"}]"""
private val mixed = """[{"path":"src/App.jsx","status":"modified","staged":"all","baseBlob":"a","indexBlob":"i"},{"path":"src/styles.css","status":"modified","staged":"none","baseBlob":"b"},{"path":"src/debug.js","status":"added","staged":"none"}]"""
private val conflict = """[{"path":"src/App.jsx","status":"modified","staged":"conflict","baseBlob":"a"},{"path":"src/styles.css","status":"modified","staged":"all","baseBlob":"b","indexBlob":"i"}]"""
private val last = """{"sha":"c41e9d2aaaa","subject":"Add a product search box above the grid","author":"claude","at":1,"pushed":false}"""
private val baseC = """[{"sha":"9f3c2a1bbbb","subject":"Cart: keep coupon on refresh","author":"deniz","at":1},{"sha":"71be004cccc","subject":"Bump checkout SDK to 4.2","author":"ahmet","at":1}]"""
private val noRemote = """{"isRepo":true,"hasRemote":false,"branch":"product-search-box","upstream":null,"project":"shop"}"""
private val unpublishedNo = """{"isRepo":true,"hasRemote":true,"branch":"product-search-box","upstream":"origin/product-search-box","ahead":0,"behind":0}"""
private val prNoRemote = """{"status":"unavailable","reason":"no_remote"}"""
private val pr = """{"status":"found","pr":{"forge":"github","number":12,"url":"https://github.com/acme/shop/pull/12","title":"Add a product search box above the grid","state":"open","draft":false,"target":"dev","head":"product-search-box",
    "review":{"state":"changes","by":["deniz"]},"checks":[{"name":"build","state":"ok","url":"u"},{"name":"unit tests","state":"bad","url":"u"},{"name":"lint","state":"ok","url":"u"}],
    "comments":[{"who":"deniz","body":"Can we debounce this? The catalog will be 2k items soon.","file":"src/App.jsx","line":17}]}}"""


/**
 * #72: Source Control in every state, as PNGs, for the side-by-side with the v4 prototype. A no-op unless SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/scm ./gradlew :ui:jvmTest --tests '*SourceControlShots*'
 */
@OptIn(ExperimentalTestApi::class)
class SourceControlShots {
    private data class Scene(
        val name: String,
        val files: String = "[]",
        val merging: Boolean = false,
        val commits: String = "[$last]",
        val incoming: Int = 0,
        val status: String = """{"isRepo":true,"hasRemote":true,"branch":"product-search-box","upstream":"origin/product-search-box","ahead":0,"behind":0}""",
        val pr: String = """{"status":"none","forge":"github"}""",
        val set: SourceControlState.() -> Unit = {},
        val click: List<String> = emptyList(),
        val popup: Boolean = false,
        val baseCommits: String = baseC,
        /** publish-new's answer: a plain 404 is a broker from before the route. */
        val publishNew: Pair<HttpStatusCode, String> = HttpStatusCode.OK to "{}",
        val typed: String? = null,
        /** GET …/git/pr/base: can a PR into the target be opened. */
        val prBase: String = """{"target":"dev","onRemote":true,"ahead":1}""",
        val prCreate: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"status":"opened","url":"u","number":1}""",
    )

    private fun api(s: Scene): SourceControlApi {
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            val body = when {
                p.endsWith("/changes") -> """{"repos":[{"repo":"","baseSha":"h","files":${s.files},"merging":${s.merging}}]}"""
                p.endsWith("/git/status") -> s.status
                p.endsWith("/git/history") -> """{"branch":"product-search-box","base":"dev","chat":${s.commits},"baseCommits":${s.baseCommits},"incoming":${s.incoming}}"""
                p.endsWith("/git/publish-new") -> return@MockEngine respond(ByteReadChannel(s.publishNew.second), s.publishNew.first, headersOf(HttpHeaders.ContentType, "text/plain"))
                p.endsWith("/git/pr/base") -> s.prBase
                p.endsWith("/git/pr") && req.method.value == "POST" -> return@MockEngine respond(ByteReadChannel(s.prCreate.second), s.prCreate.first, headersOf(HttpHeaders.ContentType, "application/json"))
                p.endsWith("/git/pr") -> s.pr
                p.endsWith("/git/branches") -> """{"current":"product-search-box","local":[{"name":"product-search-box","openIn":{"kind":"this"}},{"name":"feature/payments"},{"name":"dev","isDefault":true,"openIn":{"kind":"folder"}},{"name":"mux/refunds-endpoint","openIn":{"kind":"session","id":"s2","name":"Refunds endpoint"}}],"remote":[{"name":"origin/feature/checkout-v2"}]}"""
                p.endsWith("/settings/config") -> "{}"
                else -> "{}"
            }
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1")
    }

    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val scenes = listOf(
            Scene("01-changes", files = f3),
            Scene("02-staged-message", files = mixed, set = { message = "Add a search box" }),
            Scene("03-amend", files = mixed, set = { amend = true; message = "Add a product search box above the grid" }),
            Scene("04-nothing-staged-ask", files = f3, set = { message = "x"; ask = ScmAsk.Smart }),
            Scene("05-discard-ask", files = f3, set = { ask = ScmAsk.Discard("", "src/debug.js") }),
            Scene("06-discard-all-ask", files = f3, set = { ask = ScmAsk.DiscardAll("", 3) }),
            Scene("07-no-message", files = mixed, set = { ask = ScmAsk.NoMessage }),
            Scene("08-commit-menu", files = mixed, click = listOf("scm_commit_menu"), popup = true),
            Scene("09-more-menu", files = mixed, click = listOf("scm_more"), popup = true),
            Scene("10-picker", files = f3, set = { picking = true }),
            Scene("11-picker-create", files = f3, set = { picking = true; pickFilter = "fix/search" }),
            Scene("12-sync-ahead-behind", status = """{"isRepo":true,"hasRemote":true,"branch":"product-search-box","upstream":"origin/product-search-box","ahead":1,"behind":2}"""),
            Scene("13-unpublished", status = """{"isRepo":true,"hasRemote":true,"branch":"product-search-box","upstream":null,"ahead":0,"behind":0}"""),
            Scene("14-no-remote", status = noRemote, pr = prNoRemote),
            Scene("14b-no-remote-pick", status = noRemote, pr = prNoRemote, set = { openPublish() }),
            Scene("14c-no-remote-pick-changes", files = f3, status = noRemote, pr = prNoRemote, set = { openPublish() }),
            Scene("14d-no-remote-old-host", status = noRemote, pr = prNoRemote, set = { openPublish() }, click = listOf("scm_publish_github_private"), publishNew = HttpStatusCode.NotFound to "not found"),
            Scene("14e-no-remote-no-cli", status = noRemote, pr = prNoRemote, set = { openPublish() }, click = listOf("scm_publish_github_private"), publishNew = HttpStatusCode.Conflict to """{"ok":false,"reason":"no_cli"}"""),
            Scene("14f-no-remote-add-remote", status = noRemote, pr = prNoRemote, set = { openPublish(); publishByUrl(true); publishInput = "git@github.com:acme/shop.git" }),
            Scene("14g-no-remote-nothing-committed", files = f3, commits = "[]", baseCommits = "[]", status = noRemote, pr = prNoRemote, set = { openPublish() }),
            Scene("15-incoming", incoming = 5, files = """[{"path":"src/debug.js","status":"added","staged":"none"}]"""),
            Scene("16-conflict", files = conflict, merging = true),
            Scene("17-pr-none"),
            Scene("17d-pr-form-target-not-on-github", prBase = """{"target":"dev","onRemote":false}""",
                set = { prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What", by = "agent", loading = false, textFor = "dev") }),
            Scene("17e-pr-create-failed-details", set = { prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What", by = "agent", loading = false, textFor = "dev") },
                prCreate = HttpStatusCode.Conflict to """{"status":"unavailable","forge":"github","reason":"error","message":"pull request create failed: GraphQL: Something unexpected happened (createPullRequest)"}""",
                click = listOf("scm_pr_create_go", "scm_pr_problem_details")),
            Scene("17f-pr-form-no-commits", prBase = """{"target":"dev","onRemote":true,"ahead":0}""",
                set = { prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What", by = "agent", loading = false, textFor = "dev") }),
            Scene("17g-pr-ask-push", set = {
                prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What", by = "agent", loading = false, textFor = "dev")
                prAsk = dev.supermux.net.PrCreateResult(status = "ask", ask = "push", count = 2)
            }),
            Scene("17h-pr-ask-publish", status = """{"isRepo":true,"hasRemote":true,"remotes":["origin"],"branch":"product-search-box","upstream":null}""", set = {
                prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What", by = "agent", loading = false, textFor = "dev")
                prAsk = dev.supermux.net.PrCreateResult(status = "ask", ask = "publish")
            }),
            Scene("13b-publish-pick-remote", status = """{"isRepo":true,"hasRemote":true,"remotes":["fork","origin"],"branch":"product-search-box","upstream":null}""", set = { pickingRemote = true }),
            Scene("18-pr-form", set = { prForm = PrForm(target = "dev", title = "Add a product search box above the grid", body = "## What\n- Add a product search box above the grid", by = "agent", loading = false, textFor = "dev") }),
            Scene("19-pr-open", pr = pr),
            Scene("20-clean", commits = "[]"),
            Scene("21-merge-into", set = { ask = ScmAsk.MergeInto }),
        )
        for (s in scenes) runComposeUiTest {
            val state = SourceControlState(api(s))
            setContent {
                PlatformTestProviders(FakePlatform(), pointer = true, widthClass = WindowWidthClass.Expanded, inputMode = InputMode.Pointer, uiPrefs = UiPrefs(FakeSettingsStore())) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) {
                        Box(Modifier.size(380.dp, 860.dp)) { SourceControlPane(state, onOpenDiff = { _, _, _, _ -> }, onOpenFile = { _, _ -> }, onReview = {}) }
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) { state.repos != null && state.status != null }
            state.apply(s.set)
            if (state.picking) state.branches = kotlinx.coroutines.runBlocking { state.api.branches() }
            waitForIdle()
            // Wait for the node, and for it to be enabled (Create waits for its target check).
            s.click.forEach { tag -> waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(tag) and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }; onNodeWithTag(tag).performClick(); waitForIdle() }
            if (s.click.any { it.startsWith("scm_publish_") }) { waitUntil(timeoutMillis = 5_000) { state.publishError != null || state.note != null }; waitForIdle() }
            save(dir, s.name, this, s.popup)
        }
    }

    private fun save(dir: File, name: String, t: ComposeUiTest, popup: Boolean) {
        val roots = t.onAllNodes(isRoot()).fetchSemanticsNodes()
        ImageIO.write(t.onRoot(useUnmergedTree = true).let { if (roots.size > 1) t.onAllNodes(isRoot())[0] else it }.captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        if (popup && roots.size > 1) ImageIO.write(t.onAllNodes(isRoot())[roots.size - 1].captureToImage().toAwtImage(), "png", File(dir, "$name-popup.png"))
    }
}
