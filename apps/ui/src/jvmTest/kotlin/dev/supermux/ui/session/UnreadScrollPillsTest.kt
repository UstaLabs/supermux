package dev.supermux.ui.session

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UnreadScrollPillsTest {

    @Test
    fun splitsUnreadRowsAroundTheVisibleWindow() {
        val got = offscreenUnread(listOf(1, 3, 5, 12).map { UnreadRow(it, "t$it") }, firstVisible = 4, lastVisible = 8)
        assertEquals(
            OffscreenUnread(
                above = 2, nearestAbove = 3, below = 1, nearestBelow = 12,
                aboveTokens = setOf("t1", "t3"), belowTokens = setOf("t12"),
            ),
            got,
        )
    }

    @Test
    fun aRowOnScreenCountsNeitherWay() {
        assertEquals(OffscreenUnread(), offscreenUnread(listOf(UnreadRow(4, "a"), UnreadRow(8, "b")), firstVisible = 4, lastVisible = 8))
    }

    @Test
    fun aDismissedPillReturnsOnlyForAnUnreadStateItDidNotDismiss() {
        assertFalse(unreadPillShows(setOf("ws:w1@t1"), dismissed = setOf("ws:w1@t1", "ws:w2@t1")))
        // Same row, newer message → new token → back.
        assertTrue(unreadPillShows(setOf("ws:w1@t2"), dismissed = setOf("ws:w1@t1")))
        assertFalse(unreadPillShows(emptySet(), dismissed = emptySet()))
    }

    @Test
    fun theKeyRecorderListsEveryKeyInOrder() {
        val rec = LazyKeyRecorder().apply {
            item(key = "hdr") {}
            items(listOf("a", "b"), key = { "task:$it" }) {}
            item {}
        }
        assertEquals<List<Any?>>(listOf("hdr", "task:a", "task:b", null), rec.keys)
    }

    private val ws = (1..30).map { i ->
        workspaceDto(
            id = "w$i",
            name = "Workspace $i",
            views = listOf(workspaceChatView("v$i", "s$i", "w$i")),
            primarySessionId = "s$i",
        )
    }

    @Test
    fun anUnreadRowBelowTheFoldShowsTheDownPillAndATapScrollsToIt() = runComposeUiTest {
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = ws,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                lastBySession = mapOf(
                    "s28" to LogEntry(id = "m", ts = "2026-09-21T08:00:00Z", direction = "outbound", text = "hi"),
                ),
                lastRead = ws.associate { it.primarySessionId!! to "2026-09-21T07:00:00Z" },
                initialGroupByProject = false,
            )
        }
        onNodeWithTag(UnreadPillTestIds.ABOVE).assertDoesNotExist()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertDoesNotExist()
        onNodeWithTag(UnreadPillTestIds.ABOVE).assertDoesNotExist()
    }

    @Test
    fun theCrossHidesThePillWithoutScrolling() = runComposeUiTest {
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = ws,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                lastBySession = mapOf(
                    "s28" to LogEntry(id = "m", ts = "2026-09-21T08:00:00Z", direction = "outbound", text = "hi"),
                ),
                lastRead = ws.associate { it.primarySessionId!! to "2026-09-21T07:00:00Z" },
                initialGroupByProject = false,
            )
        }
        onNodeWithTag(UnreadPillTestIds.DISMISS_BELOW, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertDoesNotExist()
        onNodeWithText("Workspace 1").assertIsDisplayed()
    }

    @Test
    fun aTapLandsTheUnreadRowInTheMiddleOfTheList() = runComposeUiTest {
        val many = (1..60).map { i ->
            workspaceDto(
                id = "w$i", name = "Workspace $i",
                views = listOf(workspaceChatView("v$i", "s$i", "w$i")), primarySessionId = "s$i",
            )
        }
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = many,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                lastBySession = mapOf(
                    "s30" to LogEntry(id = "m", ts = "2026-09-21T08:00:00Z", direction = "outbound", text = "hi"),
                ),
                lastRead = many.associate { it.primarySessionId!! to "2026-09-21T07:00:00Z" },
                initialGroupByProject = false,
            )
        }
        onNodeWithTag(UnreadPillTestIds.BELOW).performClick()
        waitForIdle()
        val list = onNodeWithTag(WorkspaceListTestIds.LIST).fetchSemanticsNode().boundsInRoot
        val row = onNodeWithTag(WorkspaceListTestIds.row("w30"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val off = kotlin.math.abs(row.center.y - list.center.y)
        assertTrue(off < row.height, "row centre $off px from the list's centre")
    }

    @Test
    fun readRowsShowNoPill() = runComposeUiTest {
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = ws,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                initialGroupByProject = false,
            )
        }
        onNodeWithTag(UnreadPillTestIds.ABOVE).assertDoesNotExist()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertDoesNotExist()
    }

    // 30 read rows in an open project, then one unread row in a collapsed project far below.
    private val twoProjects = (1..30).map { i ->
        workspaceDto(
            id = "w$i", name = "Workspace $i", workdir = "/home/u/p/aa",
            views = listOf(workspaceChatView("v$i", "s$i", "w$i")), primarySessionId = "s$i",
        )
    } + workspaceDto(
        id = "wz", name = "Hidden unread", workdir = "/home/u/p/zz",
        views = listOf(workspaceChatView("vz", "sz", "wz")), primarySessionId = "sz",
    )

    @Test
    fun anUnreadRowInACollapsedProjectStillShowsThePillAndATapExpandsThenScrolls() = runComposeUiTest {
        var collapsed: Set<String> = setOf("/home/u/p/zz")
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = twoProjects,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                lastBySession = mapOf(
                    "sz" to LogEntry(id = "m", ts = "2026-09-21T08:00:00Z", direction = "outbound", text = "hi"),
                ),
                lastRead = twoProjects.associate { it.primarySessionId!! to "2026-09-21T07:00:00Z" },
                initialGroupByProject = true,
                initialCollapsedPaths = collapsed,
                onCollapsedPathsChange = { collapsed = it },
            )
        }
        onNodeWithText("Hidden unread").assertDoesNotExist()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(emptySet(), collapsed)
        onNodeWithText("Hidden unread").assertIsDisplayed()
        onNodeWithTag(UnreadPillTestIds.BELOW).assertDoesNotExist()
    }

    @Test
    fun aProjectHeaderCountsItsUnreadRowsOnly() = runComposeUiTest {
        // Only three rows in aa, so both headers fit on screen.
        val few = twoProjects.drop(27)
        setContent {
            SessionListScreen(
                modifier = Modifier.height(400.dp),
                workspaces = few,
                home = "/home/u",
                activeId = null,
                onOpen = {},
                lastBySession = mapOf(
                    "sz" to LogEntry(id = "m", ts = "2026-09-21T08:00:00Z", direction = "outbound", text = "hi"),
                ),
                lastRead = few.associate { it.primarySessionId!! to "2026-09-21T07:00:00Z" },
                initialGroupByProject = true,
                initialCollapsedPaths = setOf("/home/u/p/zz"),
            )
        }
        // Only the zz project has an unread row; the 30-row aa project shows no count at all.
        onAllNodesWithTag("group_unread_count", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithContentDescription("1 unread", useUnmergedTree = true).assertExists()
    }
}
