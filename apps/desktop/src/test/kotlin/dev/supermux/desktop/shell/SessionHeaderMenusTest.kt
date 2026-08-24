package dev.supermux.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.GitOpResult
import dev.supermux.net.ModelInfo
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit + runComposeUiTest coverage for the three header menus (GitBadgeMenu / SessionLinksMenu /
 * OverflowMenu). The composables take pure callbacks + state, so no DesktopAppState / network is
 * needed. The pure decision bits (Publish-vs-Push, the proxy filter, the badge label, the op-result
 * label) are asserted directly.
 */
@OptIn(ExperimentalTestApi::class)
class SessionHeaderMenusTest {

    private val baseSession = SessionInfo(
        id = "s1", name = "demo", workdir = "/w/s1", agent = "claude",
        git = GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, behind = 0, dirty = 1),
    )
    private val remoteUnpublished = baseSession.copy(
        git = GitLiteStatusDto(mode = "remote", unpublished = true),
    )
    private val remotePublished = baseSession.copy(
        git = GitLiteStatusDto(mode = "remote", ahead = 1),
    )
    private val nonRepo = baseSession.copy(git = null)

    // ── pure helpers ────────────────────────────────────────────────────────────────────

    @Test
    fun headerLabelPrefixesCompareRefForBaseKind() {
        val badge = gitBadge(baseSession.git)!!
        assertEquals(GitBadgeKind.BASE, badge.kind)
        assertEquals("main +2 ·1", headerGitBadgeLabel(badge))
        // Remote kind carries no compareRef prefix.
        assertEquals("↑1", headerGitBadgeLabel(gitBadge(remotePublished.git)!!))
    }

    @Test
    fun shouldPublishTracksUnpublishedFlag() {
        assertTrue(shouldPublish(remoteUnpublished.git))
        assertFalse(shouldPublish(remotePublished.git))
        assertFalse(shouldPublish(null))
    }

    @Test
    fun sessionProxiesFiltersByName() {
        val mine = ProxyDto(domain = "a", sessionName = "demo", port = 3000)
        val other = ProxyDto(domain = "b", sessionName = "elsewhere", port = 4000)
        assertEquals(listOf(mine), sessionProxies(listOf(mine, other), baseSession))
        assertTrue(sessionProxies(listOf(other), baseSession).isEmpty())
    }

    @Test
    fun gitOpResultLabelPrefersMessageThenStatusThenFailure() {
        assertEquals("Fetched 3 files", gitOpResultLabel("Fetch", GitOpResult(status = "ok", message = "Fetched 3 files")))
        assertEquals("up_to_date", gitOpResultLabel("Pull", GitOpResult(status = "up_to_date")))
        assertEquals("Push done", gitOpResultLabel("Push", GitOpResult()))
        assertEquals("Fetch failed", gitOpResultLabel("Fetch", null))
    }

    // ── GitBadgeMenu ───────────────────────────────────────────────────────────────────

    @Test
    fun gitBadgeRendersCountsAndOpensMenuWithPushWhenPublished() = runComposeUiTest {
        var pushed = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = remotePublished,
                    onFetch = { GitOpResult() },
                    onPull = { GitOpResult() },
                    onPush = { pushed = true; GitOpResult(status = "pushed") },
                    onPublish = { error("publish not expected when published") },
                )
            }
        }
        onNodeWithTag("git_badge").assertIsDisplayed()
        onNodeWithText("↑1").assertIsDisplayed()

        onNodeWithTag("git_badge").performClick()
        onNodeWithTag("git_fetch").assertIsDisplayed()
        onNodeWithTag("git_pull").assertIsDisplayed()
        onNodeWithTag("git_push").assertIsDisplayed()
        onNodeWithTag("git_publish").assertDoesNotExist()

        onNodeWithTag("git_push").performClick()
        waitForIdle()
        assertTrue(pushed)
        // The transient result label surfaces the op status inline (no snackbar host yet).
        onNodeWithTag("git_op_result").assertIsDisplayed()
    }

    @Test
    fun gitMenuShowsPublishWhenUnpublishedAndFiresPublish() = runComposeUiTest {
        var published = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = remoteUnpublished,
                    onFetch = { GitOpResult() },
                    onPull = { GitOpResult() },
                    onPush = { error("push not expected when unpublished") },
                    onPublish = { published = true; GitOpResult(status = "published") },
                )
            }
        }
        onNodeWithTag("git_badge").performClick()
        onNodeWithTag("git_publish").assertIsDisplayed()
        onNodeWithTag("git_push").assertDoesNotExist()

        onNodeWithTag("git_publish").performClick()
        waitForIdle()
        assertTrue(published)
    }

    @Test
    fun gitMenuFetchAndPullFireTheirCallbacks() = runComposeUiTest {
        var fetched = false
        var pulled = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = baseSession,
                    onFetch = { fetched = true; GitOpResult() },
                    onPull = { pulled = true; GitOpResult() },
                    onPush = { GitOpResult() },
                    onPublish = { GitOpResult() },
                )
            }
        }
        onNodeWithTag("git_badge").performClick()
        onNodeWithTag("git_fetch").performClick()
        waitForIdle()
        assertTrue(fetched)

        onNodeWithTag("git_badge").performClick()
        onNodeWithTag("git_pull").performClick()
        waitForIdle()
        assertTrue(pulled)
    }

    @Test
    fun gitOpResultReflectsLastLaunchedOpNotLastToComplete() = runComposeUiTest {
        // Out-of-order completion race: gate each op with a CompletableDeferred so the test controls
        // WHEN it resolves. Launch Fetch (slow) first, then reopen + launch Pull (fast); resolve Pull
        // first, then the slower Fetch LAST. The op-sequence token must keep Pull's result showing —
        // the late Fetch is stale and must not clobber it.
        val gateFetch = CompletableDeferred<GitOpResult?>()
        val gatePull = CompletableDeferred<GitOpResult?>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = baseSession,
                    onFetch = { gateFetch.await() },
                    onPull = { gatePull.await() },
                    onPush = { GitOpResult() },
                    onPublish = { GitOpResult() },
                )
            }
        }
        onNodeWithTag("git_badge").performClick()
        onNodeWithTag("git_fetch").performClick() // launches the slow op first
        onNodeWithTag("git_badge").performClick() // reopen clears + bumps the token
        onNodeWithTag("git_pull").performClick()  // launches the fast op second

        gatePull.complete(GitOpResult(status = "pulled"))
        waitForIdle()
        onNodeWithText("pulled").assertIsDisplayed()

        gateFetch.complete(GitOpResult(status = "fetched")) // the slow op resolves LAST
        waitForIdle()
        onNodeWithText("pulled").assertIsDisplayed()
        onNodeWithText("fetched").assertDoesNotExist()
    }

    // ── SM_GIT_MENU force-op hook (M4c Task 3) ──────────────────────────────────────────

    @Test
    fun forceOpenExpandsMenuAndConsumesOnceWithoutFiringAnyOp() = runComposeUiTest {
        var fetched = false
        var pulled = false
        var consumed = 0
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = baseSession,
                    onFetch = { fetched = true; GitOpResult() },
                    onPull = { pulled = true; GitOpResult() },
                    onPush = { GitOpResult() },
                    onPublish = { GitOpResult() },
                    forceOp = force,
                    onForceOpConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("git_fetch").assertDoesNotExist() // menu starts closed
        runOnIdle { force = GitMenuForceOp.OPEN }
        waitForIdle()
        onNodeWithTag("git_fetch").assertIsDisplayed()
        onNodeWithTag("git_pull").assertIsDisplayed()
        assertFalse(fetched)
        assertFalse(pulled)
        assertEquals(1, consumed)
    }

    @Test
    fun forceFetchFiresTheRealRunPathAndSurfacesTheResultLabel() = runComposeUiTest {
        var fetched = false
        var consumed = 0
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = baseSession,
                    onFetch = { fetched = true; GitOpResult(status = "fetched") },
                    onPull = { error("pull not expected from a FETCH force-op") },
                    onPush = { error("push not expected from a FETCH force-op") },
                    onPublish = { error("publish not expected from a FETCH force-op") },
                    forceOp = force,
                    onForceOpConsumed = { consumed++ },
                )
            }
        }
        runOnIdle { force = GitMenuForceOp.FETCH }
        waitForIdle()
        assertTrue(fetched)
        assertEquals(1, consumed)
        onNodeWithTag("git_op_result").assertIsDisplayed()
        onNodeWithText("fetched").assertIsDisplayed()
    }

    @Test
    fun forcePullFiresTheRealRunPathAndSurfacesTheResultLabel() = runComposeUiTest {
        var pulled = false
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = baseSession,
                    onFetch = { error("fetch not expected from a PULL force-op") },
                    onPull = { pulled = true; GitOpResult(status = "pulled") },
                    onPush = { error("push not expected from a PULL force-op") },
                    onPublish = { error("publish not expected from a PULL force-op") },
                    forceOp = force,
                )
            }
        }
        runOnIdle { force = GitMenuForceOp.PULL }
        waitForIdle()
        assertTrue(pulled)
        onNodeWithText("pulled").assertIsDisplayed()
    }

    @Test
    fun gitMenuForceOpHasNoPushOrPublishMember() {
        // Structural safety net for the SM_GIT_MENU hook: Push/Publish mutate a real remote, so no
        // env hook may ever auto-fire them — this pins the enum to exactly {OPEN, FETCH, PULL} so a
        // future edit that adds a PUSH/PUBLISH member fails loudly here instead of silently opening
        // a live-mutation hole.
        assertEquals(listOf("OPEN", "FETCH", "PULL"), GitMenuForceOp.entries.map { it.name })
    }

    @Test
    fun gitBadgeHiddenWhenGitNull() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeMenu(
                    session = nonRepo,
                    onFetch = { GitOpResult() },
                    onPull = { GitOpResult() },
                    onPush = { GitOpResult() },
                    onPublish = { GitOpResult() },
                )
            }
        }
        onNodeWithTag("git_badge").assertDoesNotExist()
    }

    // ── SessionLinksMenu ─────────────────────────────────────────────────────────────────

    @Test
    fun linksMenuListsOnlyThisSessionProxiesAndOpensProxyUrl() = runComposeUiTest {
        val mine = ProxyDto(domain = "mine.example", sessionName = "demo", port = 3000, url = "https://mine.example/")
        val other = ProxyDto(domain = "other.example", sessionName = "elsewhere", port = 4000, url = "https://other.example/")
        var opened: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionLinksMenu(
                    session = baseSession,
                    proxies = listOf(mine, other),
                    onOpenUrl = { opened = it },
                )
            }
        }
        onNodeWithTag("session_links").assertIsDisplayed()
        onNodeWithTag("session_links").performClick()
        // This session's proxy is listed (scheme-less display form); the other session's is not.
        onNodeWithText("mine.example").assertIsDisplayed()
        onNodeWithText("other.example").assertDoesNotExist()

        onNodeWithText("mine.example").performClick()
        // Opens the canonical proxyUrl (with scheme), not the display form.
        assertEquals("https://mine.example/", opened)
    }

    @Test
    fun linksMenuForceOpenExpandsAndConsumesOnceWithoutOpeningAUrl() = runComposeUiTest {
        val mine = ProxyDto(domain = "mine.example", sessionName = "demo", port = 3000)
        var opened: String? = null
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionLinksMenu(
                    session = baseSession,
                    proxies = listOf(mine),
                    onOpenUrl = { opened = it },
                    forceOpen = force,
                    onForceOpenConsumed = { consumed++ },
                )
            }
        }
        onNodeWithText("mine.example").assertDoesNotExist() // menu starts closed
        runOnIdle { force = true }
        waitForIdle()
        onNodeWithText("mine.example").assertIsDisplayed()
        assertNull(opened)
        assertEquals(1, consumed)
    }

    @Test
    fun linksMenuHiddenWhenNoProxiesForThisSession() = runComposeUiTest {
        val other = ProxyDto(domain = "other.example", sessionName = "elsewhere", port = 4000)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionLinksMenu(session = baseSession, proxies = listOf(other), onOpenUrl = {})
            }
        }
        onNodeWithTag("session_links").assertDoesNotExist()
    }

    // ── OverflowMenu ───────────────────────────────────────────────────────────────────

    @Test
    fun overflowUsageRowFiresOnUsage() = runComposeUiTest {
        var usageOpened = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = {},
                    onToggleMute = {},
                    onKill = {},
                    onUsage = { usageOpened = true },
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_usage").assertIsDisplayed()
        onNodeWithTag("overflow_usage").performClick()
        assertTrue(usageOpened)
    }

    @Test
    fun overflow_lsp_settings_row_fires_on_lsp_settings() = runComposeUiTest {
        var opened = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = {},
                    onToggleMute = {},
                    onKill = {},
                    onLspSettings = { opened = true },
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_lsp_settings").assertIsDisplayed()
        onNodeWithTag("overflow_lsp_settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun overflowRenameOpensDialogAndFiresOnRename() = runComposeUiTest {
        var renamed: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = { renamed = it },
                    onToggleMute = {},
                    onKill = {},
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_rename").performClick()
        // The dialog opens seeded with the current name.
        onNodeWithTag("overflow_rename_field").assertIsDisplayed()
        onNodeWithText("demo").assertIsDisplayed() // pre-filled with session.name
        onNodeWithTag("overflow_rename_field").performTextReplacement("renamed-demo")
        onNodeWithTag("overflow_rename_confirm").performClick()
        assertEquals("renamed-demo", renamed)
    }

    @Test
    fun overflowMuteTogglesToDesiredState() = runComposeUiTest {
        var next: Boolean? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession, // mute == null → treated as not muted
                    onRename = {},
                    onToggleMute = { next = it },
                    onKill = {},
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        // Not muted → the item reads "Mute" and requests the muted=true state.
        onNodeWithText("Mute").assertIsDisplayed()
        onNodeWithTag("overflow_mute").performClick()
        assertEquals(true, next)
    }

    @Test
    fun overflowMuteShowsUnmuteWhenMuted() = runComposeUiTest {
        var next: Boolean? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession.copy(mute = true),
                    onRename = {},
                    onToggleMute = { next = it },
                    onKill = {},
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithText("Unmute").assertIsDisplayed()
        onNodeWithTag("overflow_mute").performClick()
        assertEquals(false, next)
    }

    @Test
    fun overflowKillConfirmFiresOnKill() = runComposeUiTest {
        var killed = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = {},
                    onToggleMute = {},
                    onKill = { killed = true },
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_kill").performClick()
        assertFalse(killed) // opening the confirm dialog does not kill yet
        onNodeWithTag("overflow_kill_confirm").performClick()
        assertTrue(killed)
    }

    @Test
    fun overflowContinuePassesAgentModelAndReasoning() = runComposeUiTest {
        var received: ContinueHandoff? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession.copy(agent = "claude", model = "sonnet", reasoningLevel = "high"),
                    onRename = {},
                    onToggleMute = {},
                    onKill = {},
                    onContinue = { handoff -> received = handoff; "new-id" },
                    loadContinueAgents = { listOf("claude", "grok") },
                    loadContinueModels = { agent ->
                        if (agent == "grok") listOf(ModelInfo("grok-4", "Grok 4"))
                        else listOf(ModelInfo("sonnet", "Sonnet"))
                    },
                    loadContinueReasoning = { agent, _ ->
                        ReasoningResponse(
                            agent = agent,
                            levels = listOf(
                                ReasoningLevel("low", "Low"),
                                ReasoningLevel("high", "High"),
                            ),
                            visible = true,
                        )
                    },
                )
            }
        }
        onNodeWithTag("shell_overflow").performClick()
        onNodeWithTag("overflow_continue").performClick()
        waitForIdle()
        onNodeWithTag("overflow_continue_agent").assertIsDisplayed()
        onNodeWithTag("overflow_continue_agent").performClick()
        waitForIdle()
        onNodeWithTag("overflow_continue_agent_grok").performClick()
        waitForIdle()
        onNodeWithTag("overflow_continue_model").performClick()
        waitForIdle()
        onNodeWithTag("overflow_continue_model_grok-4").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("overflow_continue_reasoning").assertExists()
                true
            } catch (_: AssertionError) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }
        onNodeWithTag("overflow_continue_reasoning").performClick()
        waitForIdle()
        onNodeWithTag("overflow_continue_reasoning_low").performClick()
        onNodeWithTag("overflow_continue_confirm").performClick()
        waitForIdle()
        assertEquals("grok", received?.agent)
        assertEquals("grok-4", received?.model)
        assertEquals("low", received?.reasoningLevel)
        assertTrue(received?.message?.isNotBlank() == true)
    }

    @Test
    fun overflowForceOpenExpandsAndConsumesOnceWithoutFiringAnyAction() = runComposeUiTest {
        var renamed: String? = null
        var muted: Boolean? = null
        var killed = false
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = { renamed = it },
                    onToggleMute = { muted = it },
                    onKill = { killed = true },
                    forceOpen = force,
                    onForceOpenConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("overflow_rename").assertDoesNotExist() // menu starts closed
        runOnIdle { force = true }
        waitForIdle()
        onNodeWithTag("overflow_rename").assertIsDisplayed()
        onNodeWithTag("overflow_mute").assertIsDisplayed()
        onNodeWithTag("overflow_kill").assertIsDisplayed()
        assertNull(renamed)
        assertNull(muted)
        assertFalse(killed)
        assertEquals(1, consumed)
    }

    @Test
    fun badgeLabelNullForNonRepoSession() {
        assertNull(gitBadge(nonRepo.git))
    }
}
