package dev.supermux.ui.session

import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared workspace row (cluster F3), branched on [LocalInputMode]: desktop's sidebar row under
 * [InputMode.Pointer] (hover + right-click, preview + branch, no swipe), Android's phone card under
 * [InputMode.Touch] (swipe actions, path label, git badge, overflow menu).
 *
 * The model half of the row lives in [WorkspaceListTest]; this pins what each branch RENDERS.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceRowTest {

    private val git = GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, dirty = 1)

    private fun session(id: String, name: String = id, git: GitLiteStatusDto? = null) =
        SessionInfo(id = id, name = name, workdir = "/home/u/projects/app", agent = "claude", git = git)

    private fun model(
        name: String = "Fix it",
        git: GitLiteStatusDto? = null,
        agentState: Map<String, AgentStatus> = emptyMap(),
    ): WorkspaceRowModel {
        val w = workspaceDto(
            id = "w1",
            name = name,
            views = listOf(workspaceChatView("v1", "s1", "w1")),
            primarySessionId = "s1",
        )
        return deriveWorkspaceRow(
            w = w,
            sessionsById = mapOf("s1" to session("s1", name, git)),
            agentState = agentState,
            lastBySession = emptyMap(),
            lastRead = emptyMap(),
            home = "/home/u",
            selectedSessionId = null,
        )
    }

    @Composable
    private fun Row(
        mode: InputMode,
        model: WorkspaceRowModel = model(),
        preview: LogEntry? = null,
        mute: Boolean = false,
        interactionSource: MutableInteractionSource? = null,
        onKill: () -> Unit = {},
        onToggleMute: () -> Unit = {},
        onNewChat: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalInputMode provides mode) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Column {
                    WorkspaceRow(
                        model = model,
                        active = false,
                        preview = preview,
                        mute = mute,
                        interactionSource = interactionSource,
                        onClick = {},
                        onKill = onKill,
                        onToggleMute = onToggleMute,
                        onNewChat = onNewChat,
                    )
                }
            }
        }
    }

    // ── Touch branch ─────────────────────────────────────────────────────────────────────────

    @Test fun touchRow_swipeRevealsTheMuteAndArchiveActions() = runComposeUiTest {
        var muted = 0
        setContent { Row(InputMode.Touch, onToggleMute = { muted++ }) }
        // The touch row's Surface merges the row's semantics into its own clickable node.
        onNodeWithTag(WorkspaceListTestIds.row("w1"), useUnmergedTree = true)
            .performTouchInput { swipeRight() }
        waitForIdle()
        // The swipe only REVEALS — nothing fires until the revealed button is tapped.
        assertEquals(0, muted)
        onNodeWithText("Mute").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, muted)
    }

    @Test fun touchRow_showsThePathLabelAndTheGitBadge() = runComposeUiTest {
        setContent { Row(InputMode.Touch, model = model(git = git)) }
        onNodeWithText("…/projects/app").assertIsDisplayed()
        onNodeWithText("+2 ·1").assertIsDisplayed()
    }

    @Test fun touchRow_overflowMenuOffersRenameAndNewChatHere() = runComposeUiTest {
        var newChats = 0
        setContent { Row(InputMode.Touch, onNewChat = { newChats++ }) }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Rename").assertIsDisplayed()
        onNodeWithTag(WorkspaceListTestIds.ROW_NEW_CHAT, useUnmergedTree = true).assertExists()
        onNodeWithText("New chat here").performClick()
        waitForIdle()
        assertEquals(1, newChats)
    }

    // ── Pointer branch ───────────────────────────────────────────────────────────────────────

    @Test fun pointerRow_hasNoSwipeActionsAndShowsThePreview() = runComposeUiTest {
        var muted = 0
        setContent {
            Row(
                InputMode.Pointer,
                preview = LogEntry(id = "m", ts = "2026-08-01T12:00:00.000Z", direction = "out", text = "hello there"),
                onToggleMute = { muted++ },
            )
        }
        onNodeWithText("hello there").assertIsDisplayed()
        onNodeWithTag(WorkspaceListTestIds.row("w1")).performTouchInput { swipeRight() }
        waitForIdle()
        // No swipe layer under Pointer: nothing is revealed and nothing fires.
        onNodeWithText("Mute").assertDoesNotExist()
        assertEquals(0, muted)
    }

    @Test fun pointerRow_hoverIsWiredToTheRowsInteractionSource() = runComposeUiTest {
        val interactions = mutableListOf<Any>()
        setContent {
            val src = remember { MutableInteractionSource() }
            LaunchedEffect(src) { src.interactions.collect { interactions += it } }
            Row(InputMode.Pointer, interactionSource = src)
        }
        onNodeWithTag(WorkspaceListTestIds.row("w1")).performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(
            interactions.any { it is HoverInteraction.Enter },
            "the pointer row must report hover: $interactions",
        )
    }

    @Test fun pointerRow_rightClickMenuOffersRenameMuteArchive() {
        assertEquals(listOf("Rename", "Mute", "Archive"), workspaceRowContextLabels(mute = false))
        assertEquals(listOf("Rename", "Unmute", "Archive"), workspaceRowContextLabels(mute = true))
        assertEquals(listOf("Restore"), archivedWorkspaceRowContextLabels())
    }

    // ── Archived row ─────────────────────────────────────────────────────────────────────────

    @Test fun archivedRow_touchShowsPathAndTheRestoreMenu() = runComposeUiTest {
        var restored = 0
        setContent {
            val m = deriveArchivedWorkspaceRow(
                workspaceDto(id = "a1", name = "Old", status = "archived", archivedAt = "2026-08-02T00:00:00Z"),
                home = "/home/u",
            )
            CompositionLocalProvider(LocalInputMode provides InputMode.Touch) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    ArchivedWorkspaceRow(model = m, onSelect = {}, onRestore = { restored++ })
                }
            }
        }
        onNodeWithTag(WorkspaceListTestIds.archived("a1")).assertIsDisplayed()
        onNodeWithText("…/projects/app").assertIsDisplayed()
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Restore").performClick()
        waitForIdle()
        assertEquals(1, restored)
    }

    @Test fun archivedRow_pointerShowsJustTheName() = runComposeUiTest {
        setContent {
            val m = deriveArchivedWorkspaceRow(
                workspaceDto(id = "a1", name = "Old", status = "archived", archivedAt = "2026-08-02T00:00:00Z"),
                home = "/home/u",
            )
            CompositionLocalProvider(LocalInputMode provides InputMode.Pointer) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    ArchivedWorkspaceRow(model = m, onSelect = {}, onRestore = {})
                }
            }
        }
        onNodeWithTag(WorkspaceListTestIds.archived("a1")).assertIsDisplayed()
        onNodeWithText("Old").assertIsDisplayed()
        // Desktop's archived row is name-only; path and the overflow menu are the phone's.
        onNodeWithText("…/projects/app").assertDoesNotExist()
        onNodeWithContentDescription("More").assertDoesNotExist()
    }

    @Test fun archivedFoldButton_labelFlipsWithTheFold() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Column {
                    ArchivedFoldButton(count = 3, expanded = false, onClick = {})
                }
            }
        }
        onNodeWithTag(WorkspaceListTestIds.ARCHIVED_FOLD).assertIsDisplayed()
        onNodeWithText("Show 3 archived").assertIsDisplayed()
    }
}
