package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.LocalKeyScope
import dev.supermux.ui.keys.RegisterCommand
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
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
    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

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
    fun aHiddenWorkspaceRegistersNoCommands() = runComposeUiTest {
        val runs = mutableListOf<String>()
        var activeWorkspaceId by mutableStateOf("w1")
        var extraRetainIds by mutableStateOf(emptySet<String>())
        setContent {
            ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(200.dp).testTag("root")) {
                WorkspaceKeepAliveHost(
                    activeWorkspaceId = activeWorkspaceId,
                    liveWorkspaceIds = setOf("w1", "w2"),
                    extraRetainIds = extraRetainIds,
                ) { workspaceId, _ ->
                    // As a Files pane does: go to file from anywhere in the window.
                    RegisterCommand(AppCommands.GoToFile, scope = LocalKeyScope.current?.root) { runs += workspaceId; true }
                }
            }
        }
        fun goToFile() {
            onNodeWithTag("root").performKeyInput { withKeyDown(mod) { pressKey(Key.P) } }
            waitForIdle()
        }
        waitForIdle()

        // w2 is mounted after w1 registered, kept alive but hidden (an extra window shows its slice).
        extraRetainIds = setOf("w2")
        waitForIdle()
        goToFile()
        assertEquals(listOf("w1"), runs, "the hidden workspace registered nothing, though it came last")

        // Shown, w2 takes the key; hidden again, it gives it back.
        activeWorkspaceId = "w2"
        waitForIdle()
        goToFile()
        activeWorkspaceId = "w1"
        waitForIdle()
        goToFile()
        assertEquals(listOf("w1", "w2", "w1"), runs)
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
