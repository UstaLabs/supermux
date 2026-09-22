package dev.supermux.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitBadgeTone
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.gitBadge
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared git badge row (was Android's `session/GitBadge.kt`, `R.drawable.ic_git_branch` now the
 * Material `CallSplit` glyph [SessionStatusRail] already uses). The badge TEXT is derived by
 * `:shared`'s [gitBadge]; this pins what the row paints for each state.
 */
@OptIn(ExperimentalTestApi::class)
class GitBadgeRowTest {

    private fun render(git: GitLiteStatusDto?) = git

    @Test fun baseModeShowsTheBranchGlyphAndCounts() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeRow(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, dirty = 1))
            }
        }
        onNodeWithText("+2 ·1").assertIsDisplayed()
        // BASE is the only kind with the branch icon.
        assertEquals(
            GitBadgeKind.BASE,
            gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, dirty = 1))?.kind,
        )
    }

    @Test fun remoteModeShowsArrowsAndNoBranchGlyph() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeRow(GitLiteStatusDto(mode = "remote", compareRef = "origin/main", ahead = 1, behind = 3))
            }
        }
        onNodeWithText("↑1 ↓3").assertIsDisplayed()
        assertEquals(
            GitBadgeKind.REMOTE,
            gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "origin/main", ahead = 1))?.kind,
        )
    }

    @Test fun inSyncIsTheMutedCheck() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeRow(GitLiteStatusDto(mode = "base", compareRef = "main"))
            }
        }
        onNodeWithText("✓").assertIsDisplayed()
        assertEquals(GitBadgeTone.MUTED, gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main"))?.tone)
    }

    @Test fun unpublishedRemoteSaysSo() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeRow(GitLiteStatusDto(mode = "remote", compareRef = "main", unpublished = true))
            }
        }
        onNodeWithText("unpublished").assertIsDisplayed()
    }

    @Test fun aNonRepoSessionRendersNothingAtAll() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                GitBadgeRow(render(null))
            }
        }
        val tree = onRoot().printToString()
        assertFalse(tree.contains("✓"), tree)
        assertTrue(tree.isNotEmpty())
    }

    @Test fun theHeaderLabelPrefixesTheCompareRefForBaseOnly() {
        val base = gitBadge(GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, dirty = 1))!!
        assertEquals("main +2 ·1", headerGitBadgeLabel(base))
        val remote = gitBadge(GitLiteStatusDto(mode = "remote", compareRef = "origin/main", ahead = 1))!!
        assertEquals("↑1", headerGitBadgeLabel(remote))
    }
}
