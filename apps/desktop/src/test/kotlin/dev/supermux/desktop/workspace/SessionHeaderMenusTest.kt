package dev.supermux.desktop.workspace

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
import dev.supermux.net.ProxyDto
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
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
        onNodeWithTag("workspace_overflow").performClick()
        onNodeWithTag("overflow_rename").performClick()
        // The dialog opens seeded with the current name.
        onNodeWithTag("overflow_rename_field").assertIsDisplayed()
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
        onNodeWithTag("workspace_overflow").performClick()
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
        onNodeWithTag("workspace_overflow").performClick()
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
        onNodeWithTag("workspace_overflow").performClick()
        onNodeWithTag("overflow_kill").performClick()
        assertFalse(killed) // opening the confirm dialog does not kill yet
        onNodeWithTag("overflow_kill_confirm").performClick()
        assertTrue(killed)
    }

    @Test
    fun badgeLabelNullForNonRepoSession() {
        assertNull(gitBadge(nonRepo.git))
    }
}
