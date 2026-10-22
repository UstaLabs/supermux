package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The selected row scrolls away and back like every other row. It used to take focus on click, a
 * focused lazy item is pinned (kept laid out off-screen at a stale slot), and on the way back
 * `animateItem()` slid it from that slot into place, over its neighbours.
 */
@OptIn(ExperimentalTestApi::class)
class SelectedRowScrollTest {

    @Test
    fun theSelectedRowIsNotPinnedAndComesBackWithoutSliding() = runComposeUiTest {
        val workspaces = (1..40).map { i ->
            workspaceDto(id = "w$i", name = "Workspace $i", workdir = "/home/u/projects/app")
        }
        lateinit var listState: LazyListState
        setContent {
            var active by remember { mutableStateOf<String?>(null) }
            listState = rememberLazyListState()
            Box(Modifier.width(300.dp).height(400.dp)) {
                SessionListScreen(
                    workspaces = workspaces,
                    home = "/home/u",
                    activeId = active,
                    onOpen = { active = it },
                    listState = listState,
                )
            }
        }
        waitForIdle()
        // The second workspace row on screen (whatever the list's order), so it starts visible.
        val id = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { (it.key as? String)?.removePrefix("ws:")?.takeIf { k -> k != it.key } }[1]
        val row = onNodeWithTag(WorkspaceListTestIds.row(id), useUnmergedTree = true)
        row.performMouseInput { click() }
        waitForIdle()
        val start = row.fetchSemanticsNode().positionInRoot.y

        // Away: far past the viewport. A row that scrolled away is disposed, selected or not.
        var scrolled = 0f
        runOnUiThread { scrolled = listState.dispatchRawDelta(1500f) }
        waitForIdle()
        row.assertDoesNotExist()

        // And back, frame by frame: on the first frame it is visible it must sit in its slot.
        mainClock.autoAdvance = false
        runOnUiThread { listState.dispatchRawDelta(-scrolled) }
        mainClock.advanceTimeByFrame()
        val tops = buildList {
            repeat(6) {
                add(row.fetchSemanticsNode().positionInRoot.y)
                mainClock.advanceTimeByFrame()
            }
        }
        assertTrue(tops.all { abs(it - start) < 1f }, "row slid back into place: start=$start frames=$tops")
    }
}
