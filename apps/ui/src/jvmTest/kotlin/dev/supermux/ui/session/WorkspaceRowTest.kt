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
 * The shared workspace row (cluster F3): desktop's sidebar row on EVERY host since the session
 * lists unified (b56f0a70). The touch card — swipe actions, path label, git badge — is gone; what
 * varies is only where the actions live: a right-click menu where the platform has one, the `⋮`
 * overflow where it does not (a phone). The path is the group header's, not the row's.
 *
 * The model half of the row lives in [WorkspaceListTest]; this pins what the row RENDERS under
 * each input mode.
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
        contextMenu: Boolean = true,
        onKill: () -> Unit = {},
        onToggleMute: () -> Unit = {},
        onNewChat: () -> Unit = {},
        onRename: () -> Unit = {},
    ) {
        CompositionLocalProvider(
            LocalInputMode provides mode,
            LocalContextMenuAvailable provides contextMenu,
        ) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Column {
                    WorkspaceRow(
                        model = model,
                        active = false,
                        preview = preview,
                        mute = mute,
                        interactionSource = interactionSource,
                        onClick = {},
                        onRename = onRename,
                        onKill = onKill,
                        onToggleMute = onToggleMute,
                        onNewChat = onNewChat,
                    )
                }
            }
        }
    }

    // ── Touch (a phone: no platform context menu) ────────────────────────────────────────────

    @Test fun touchRow_hasNoSwipeLayer_theActionsLiveInTheOverflow() = runComposeUiTest {
        var muted = 0
        setContent { Row(InputMode.Touch, contextMenu = false, onToggleMute = { muted++ }) }
        onNodeWithTag(WorkspaceListTestIds.row("w1"), useUnmergedTree = true)
            .performTouchInput { swipeRight() }
        waitForIdle()
        // Swipe is gone with the touch card: nothing is revealed and nothing fires.
        onNodeWithText("Mute").assertDoesNotExist()
        assertEquals(0, muted)
        // The same action is one tap away in the `⋮` overflow.
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Mute").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, muted)
    }

    @Test fun touchRow_isDesktopsLeanRow_noPathLabel() = runComposeUiTest {
        setContent { Row(InputMode.Touch, contextMenu = false, model = model(git = git)) }
        onNodeWithTag(WorkspaceListTestIds.row("w1"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Fix it").assertIsDisplayed()
        // The path is the group header's; the row does not repeat it on any host.
        onNodeWithText("…/projects/app").assertDoesNotExist()
    }

    @Test fun touchRow_overflowMenuOffersRenameAndNewChatHere() = runComposeUiTest {
        var newChats = 0
        setContent { Row(InputMode.Touch, contextMenu = false, onNewChat = { newChats++ }) }
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
        // With a real context menu the row carries no visible overflow — right-click owns it.
        onNodeWithContentDescription("More").assertDoesNotExist()
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

    @Test fun pointerRow_withoutAContextMenuFallsBackToAnOverflowWithEveryAction() = runComposeUiTest {
        // Android in Pointer mode (DeX, Chromebook, docked tablet, keyboard case): the lean row is
        // the one that renders, and its right-click menu is inert — the actions must still be there.
        var renamed = 0
        var newChats = 0
        var muted = 0
        var killed = 0
        setContent {
            Row(
                InputMode.Pointer, contextMenu = false,
                onKill = { killed++ }, onToggleMute = { muted++ },
                onNewChat = { newChats++ }, onRename = { renamed++ },
            )
        }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithTag(WorkspaceListTestIds.ROW_NEW_CHAT, useUnmergedTree = true).assertExists()
        for (label in listOf("Rename", "New chat here", "Mute", "Archive")) {
            onNodeWithText(label).assertIsDisplayed()
        }
        onNodeWithText("Rename").performClick(); waitForIdle()
        onNodeWithContentDescription("More").performClick(); waitForIdle()
        onNodeWithText("New chat here").performClick(); waitForIdle()
        onNodeWithContentDescription("More").performClick(); waitForIdle()
        onNodeWithText("Mute").performClick(); waitForIdle()
        onNodeWithContentDescription("More").performClick(); waitForIdle()
        onNodeWithText("Archive").performClick(); waitForIdle()
        assertEquals(listOf(1, 1, 1, 1), listOf(renamed, newChats, muted, killed))
    }

    @Test fun pointerRow_withoutAContextMenuShowsUnmuteWhenMuted() = runComposeUiTest {
        setContent { Row(InputMode.Pointer, contextMenu = false, mute = true) }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Unmute").assertIsDisplayed()
    }

    @Test fun pointerRow_rightClickMenuOffersRenameMuteArchive() {
        assertEquals(listOf("Rename", "Mute", "Archive"), workspaceRowContextLabels(mute = false))
        assertEquals(listOf("Rename", "Unmute", "Archive"), workspaceRowContextLabels(mute = true))
        assertEquals(listOf("Restore"), archivedWorkspaceRowContextLabels())
    }

    // ── Archived row ─────────────────────────────────────────────────────────────────────────

    @Test fun archivedRow_touchShowsTheNameAndTheRestoreMenu() = runComposeUiTest {
        var restored = 0
        setContent {
            val m = deriveArchivedWorkspaceRow(
                workspaceDto(id = "a1", name = "Old", status = "archived", archivedAt = "2026-08-02T00:00:00Z"),
                home = "/home/u",
            )
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalContextMenuAvailable provides false,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    ArchivedWorkspaceRow(model = m, onSelect = {}, onRestore = { restored++ })
                }
            }
        }
        onNodeWithTag(WorkspaceListTestIds.archived("a1")).assertIsDisplayed()
        onNodeWithText("Old").assertIsDisplayed()
        // Name-only on every host; the phone's path label went with the touch card.
        onNodeWithText("…/projects/app").assertDoesNotExist()
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
        // Name-only, and with a real context menu no visible overflow either.
        onNodeWithText("…/projects/app").assertDoesNotExist()
        onNodeWithContentDescription("More").assertDoesNotExist()
    }

    @Test fun archivedRow_pointerWithoutAContextMenuKeepsRestore() = runComposeUiTest {
        var restored = 0
        setContent {
            val m = deriveArchivedWorkspaceRow(
                workspaceDto(id = "a1", name = "Old", status = "archived", archivedAt = "2026-08-02T00:00:00Z"),
                home = "/home/u",
            )
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Pointer,
                LocalContextMenuAvailable provides false,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    ArchivedWorkspaceRow(model = m, onSelect = {}, onRestore = { restored++ })
                }
            }
        }
        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("Restore").performClick()
        waitForIdle()
        assertEquals(1, restored)
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
