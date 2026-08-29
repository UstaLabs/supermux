package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class WorkspaceProbe {
    var mounts = 0
    var disposals = 0
}

@Composable
private fun ProbeWorkspace(id: String, probe: WorkspaceProbe) {
    LaunchedEffect(Unit) { probe.mounts += 1 }
    DisposableEffect(Unit) { onDispose { probe.disposals += 1 } }
    Box(Modifier.fillMaxSize().testTag("content-$id"))
}

@OptIn(ExperimentalTestApi::class)
class WorkspaceKeepAliveTest {
    @Test
    fun selectedWorkspaceIsMostRecentAndNeverDuplicated() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)

        assertEquals(listOf("w1"), cache.update("w1", setOf("w1", "w2")))
        assertEquals(listOf("w1", "w2"), cache.update("w2", setOf("w1", "w2")))
        assertEquals(listOf("w2", "w1"), cache.update("w1", setOf("w1", "w2")))
    }

    @Test
    fun eleventhWorkspaceEvictsTheLeastRecentlyViewed() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = (1..11).map { "w$it" }.toSet()

        (1..11).forEach { cache.update("w$it", live) }

        assertEquals((2..11).map { "w$it" }, cache.update("w11", live))
    }

    @Test
    fun removedWorkspacesArePrunedImmediately() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        cache.update("w1", setOf("w1", "w2"))
        cache.update("w2", setOf("w1", "w2"))

        assertEquals(listOf("w2"), cache.update("w2", setOf("w2")))
    }

    @Test
    fun extraRetainIdsAreNotEvictedByAnEleventhVisit() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = (1..11).map { "w$it" }.toSet()
        (1..10).forEach { cache.update("w$it", live) }
        val kept = cache.preview("w11", live, extraIds = setOf("w1"))
        assertTrue("w1" in kept)
        assertTrue("w11" in kept)
        assertEquals(10, kept.size)
    }

    @Test
    fun previewDoesNotMutateRetentionUntilCommitted() {
        val cache = WorkspaceKeepAliveCache(maxSize = 10)
        val live = setOf("w1", "w2")
        cache.update("w1", live)

        val candidate = cache.preview("w2", live)

        assertEquals(listOf("w1", "w2"), candidate)
        assertEquals(
            listOf("w1"),
            cache.preview(activeWorkspaceId = null, liveWorkspaceIds = live),
        )

        cache.commit(candidate)

        assertEquals(
            listOf("w1", "w2"),
            cache.preview(activeWorkspaceId = null, liveWorkspaceIds = live),
        )
    }

    @Test
    fun switchingAwayAndBackDoesNotDisposeOrRemountAWorkspace() = runComposeUiTest {
        val probes = mapOf("w1" to WorkspaceProbe(), "w2" to WorkspaceProbe())
        var activeWorkspaceId by mutableStateOf("w1")
        setContent {
            WorkspaceKeepAliveHost(
                activeWorkspaceId = activeWorkspaceId,
                liveWorkspaceIds = probes.keys,
            ) { workspaceId, _ ->
                ProbeWorkspace(workspaceId, probes.getValue(workspaceId))
            }
        }

        onNodeWithTag("content-w1").assertIsDisplayed()
        activeWorkspaceId = "w2"
        waitForIdle()

        assertEquals(0.dp, onNodeWithTag("content-w1").getBoundsInRoot().width)
        assertEquals(1, probes.getValue("w1").mounts)
        assertEquals(0, probes.getValue("w1").disposals)

        activeWorkspaceId = "w1"
        waitForIdle()

        onNodeWithTag("content-w1").assertIsDisplayed()
        assertEquals(1, probes.getValue("w1").mounts)
        assertEquals(0, probes.getValue("w1").disposals)
    }

    @Test
    fun retainedWorkspaceKeepsItsLazyListPosition() = runComposeUiTest {
        var activeWorkspaceId by mutableStateOf("w1")
        setContent {
            WorkspaceKeepAliveHost(
                activeWorkspaceId = activeWorkspaceId,
                liveWorkspaceIds = setOf("w1", "w2"),
            ) { workspaceId, _ ->
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("list-$workspaceId"),
                    state = listState,
                ) {
                    items((0 until 100).toList()) { index ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                                .testTag("$workspaceId-item-$index"),
                        )
                    }
                }
            }
        }

        onNodeWithTag("list-w1").performScrollToIndex(60)
        onNodeWithTag("w1-item-60").assertIsDisplayed()

        activeWorkspaceId = "w2"
        waitForIdle()
        activeWorkspaceId = "w1"
        waitForIdle()

        onNodeWithTag("w1-item-60").assertIsDisplayed()
    }

    @Test
    fun eleventhWorkspaceDisposesTheLeastRecentLayer() = runComposeUiTest {
        val probes = (1..11).associate { "w$it" to WorkspaceProbe() }
        var activeWorkspaceId by mutableStateOf("w1")
        setContent {
            WorkspaceKeepAliveHost(
                activeWorkspaceId = activeWorkspaceId,
                liveWorkspaceIds = probes.keys,
            ) { workspaceId, _ ->
                ProbeWorkspace(workspaceId, probes.getValue(workspaceId))
            }
        }

        (2..10).forEach { index ->
            activeWorkspaceId = "w$index"
            waitForIdle()
        }

        assertEquals(1, probes.getValue("w1").mounts)
        assertEquals(0, probes.getValue("w1").disposals)
        (1..10).forEach { index ->
            onNodeWithTag("content-w$index").assertExists()
        }

        activeWorkspaceId = "w11"
        waitForIdle()

        assertEquals(1, probes.getValue("w1").disposals)
        onNodeWithTag("content-w1").assertDoesNotExist()
        (2..11).forEach { index ->
            onNodeWithTag("content-w$index").assertExists()
        }
        onNodeWithTag("content-w11").assertIsDisplayed()
    }

    @Test
    fun showActiveFalseKeepsTheActiveWorkspaceMountedButZeroSized() = runComposeUiTest {
        val probe = WorkspaceProbe()
        var showActive by mutableStateOf(true)
        var contentActive: Boolean? = null
        setContent {
            WorkspaceKeepAliveHost(
                activeWorkspaceId = "w1",
                liveWorkspaceIds = setOf("w1"),
                showActive = showActive,
            ) { workspaceId, active ->
                contentActive = active
                ProbeWorkspace(workspaceId, probe)
            }
        }

        waitForIdle()

        assertEquals(true, contentActive)
        onNodeWithTag("content-w1").assertIsDisplayed()

        showActive = false
        waitForIdle()

        assertEquals(1, probe.mounts)
        assertEquals(0, probe.disposals)
        assertEquals(false, contentActive)
        assertEquals(0.dp, onNodeWithTag("content-w1").getBoundsInRoot().width)
    }

    @Test
    fun sameInstanceMutableLiveSetIsSnapshottedOnEveryComposition() = runComposeUiTest {
        val liveWorkspaceIds = mutableSetOf("w1", "w2")
        val probe = WorkspaceProbe()
        var recompositionTick by mutableStateOf(0)
        setContent {
            WorkspaceKeepAliveHost(
                activeWorkspaceId = "w1",
                liveWorkspaceIds = liveWorkspaceIds,
                modifier = Modifier.testTag("workspace-host-$recompositionTick"),
            ) { workspaceId, _ ->
                ProbeWorkspace(workspaceId, probe)
            }
        }

        onNodeWithTag("content-w1").assertIsDisplayed()

        liveWorkspaceIds.remove("w1")
        recompositionTick += 1
        waitForIdle()

        assertEquals(1, probe.disposals)
        onNodeWithTag("content-w1").assertDoesNotExist()
    }
}
