package dev.supermux.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun view(id: String, sessionId: String, wid: String) = ViewDto(
    id = id, workspaceId = wid, kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

private fun workspace(id: String, name: String, workdir: String, views: List<ViewDto> = emptyList()) =
    WorkspaceDto(
        id = id, name = name, workdir = workdir, views = views,
        layout = LayoutNodeDto.Group(id = "g", viewIds = views.map { it.id }),
    )

/**
 * The shared [SessionListScreen] under Compact/Touch: since the lists unified (b56f0a70) the rows
 * have no swipe layer on any host — a phone reaches the row actions through the `⋮` overflow —
 * and the group-by toggle is the section header's on every width. Plus the collapsed project set
 * surviving a remount (the F1 review flagged that it was per-mount).
 */
@OptIn(ExperimentalTestApi::class)
class SessionListScreenTouchTest {

    @Test fun under_touch_archive_is_in_the_overflow_not_a_swipe() = runComposeUiTest {
        var archived: String? = null
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
                // A phone has no platform context menu, so the row shows its `⋮` overflow.
                LocalContextMenuAvailable provides false,
                // The archive confirm dialog reads the platform's notices.
                LocalPlatform provides FakePlatform(),
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    workspaces = listOf(workspace("w1", "solo", "/home/u/p", listOf(view("v1", "s1", "w1")))),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    openWorkspaceByWorkspaceId = false,
                    actions = SessionListActions(archiveWorkspace = { archived = it }),
                )
            }
        }
        onNodeWithTag("workspace_row_w1", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        // No swipe layer: a swipe reveals nothing and fires nothing.
        onNodeWithText("Archive").assertDoesNotExist()
        assertEquals(null, archived)
        // The action lives in the overflow, and still goes through the screen's confirm dialog.
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Archive").assertIsDisplayed().performClick()
        onNodeWithText("Archive workspace?").assertIsDisplayed()
        assertEquals(null, archived)
        onNodeWithText("This archives \"solo\" and ends its agents. This can't be undone.")
            .assertIsDisplayed()
    }

    @Test fun the_pointer_branch_has_no_swipe() = runComposeUiTest {
        setContent {
            SessionListScreen(
                workspaces = listOf(workspace("w1", "solo", "/home/u/p", listOf(view("v1", "s1", "w1")))),
                home = "/home/u",
                activeId = null,
                onOpen = {},
            )
        }
        onNodeWithTag("workspace_row_w1").performTouchInput { swipeLeft() }
        onNodeWithText("Archive").assertDoesNotExist()
    }

    /**
     * The host reads the collapsed set from the shared store and re-supplies it, so tearing the
     * screen down (phone list ↔ chat) and putting it back must not expand the group again.
     */
    @Test fun the_collapsed_set_survives_a_remount() = runComposeUiTest {
        var emitted: Set<String> = emptySet()
        var mounted by mutableStateOf(true)
        setContent {
            if (mounted) {
                SessionListScreen(
                    workspaces = listOf(workspace("w1", "live", "/home/u/projects/app")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    initialCollapsedPaths = emitted,
                    onCollapsedPathsChange = { emitted = it },
                )
            }
        }
        onNodeWithText("live").assertIsDisplayed()
        onNodeWithText("app").performClick()
        onNodeWithText("live").assertDoesNotExist()
        assertTrue(emitted.isNotEmpty(), "collapsing a group must reach the store")

        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()
        onNodeWithText("live").assertDoesNotExist()
    }

    /** A set supplied from OUTSIDE (a host switch) replaces what this composition was showing. */
    @Test fun an_externally_supplied_collapsed_set_wins() = runComposeUiTest {
        var supplied by mutableStateOf(setOf<String>())
        setContent {
            SessionListScreen(
                workspaces = listOf(workspace("w1", "live", "/home/u/projects/app")),
                home = "/home/u",
                activeId = null,
                onOpen = {},
                initialCollapsedPaths = supplied,
            )
        }
        onNodeWithText("live").assertIsDisplayed()
        supplied = setOf("/home/u/projects/app")
        waitForIdle()
        onNodeWithText("live").assertDoesNotExist()
    }

    @Test fun the_group_by_toggle_reports_its_new_value() = runComposeUiTest {
        val seen = mutableListOf<Boolean>()
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SessionListScreen(
                    mode = SessionListMode.Fleet,
                    workspaces = listOf(workspace("w1", "solo", "/home/u/projects/app")),
                    home = "/home/u",
                    activeId = null,
                    onOpen = {},
                    initialGroupByProject = false,
                    onGroupByProjectChange = { seen += it },
                )
            }
        }
        // The section header's toggle — the same control desktop's sidebar has.
        onNodeWithTag("sidebar_group_toggle").performClick()
        assertEquals(listOf(true), seen)
    }
}
