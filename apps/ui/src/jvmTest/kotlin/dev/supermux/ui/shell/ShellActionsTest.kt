package dev.supermux.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NO_CAPS
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shell holder (cluster G1): its defaults are the "no broker" answers, the walkthrough holder
 * is gated on the capability at BUILD time (reading it on a store with no seam throws), and the
 * single-host builder wires every member onto its store.
 */
@OptIn(ExperimentalTestApi::class)
class ShellActionsTest {

    @Test
    fun `the defaults answer nothing rather than throwing`() = runBlocking {
        val actions = ShellActions()
        assertNull(actions.connectAgentTerminal("s1"))
        assertNull(actions.connectTerminal("s1", "main"))
        assertNull(actions.connectWorkspaceTerminal("w1", "main"))
        assertTrue(actions.workspaceFsListResult("w1", ".").getOrThrow().isEmpty())
        assertTrue(actions.workspaceFsRead("w1", "a.txt").isFailure)
        assertEquals(false, actions.workspaceFsWrite("w1", "a.txt", "x"))
        assertTrue(actions.workspaceFsSearch("w1", "q").isEmpty())
        assertNull(actions.workspaceFsDiff("w1", null))
        assertNull(actions.workspaceFsRefs("w1"))
        assertTrue(actions.reviewComments("s1").isEmpty())
        assertNull(
            actions.reviewAddComment(
                "s1",
                dev.supermux.net.AddCommentBody(
                    repo = "r", path = "a.kt", side = "new", anchorLine = 1,
                    anchorContext = "ctx", body = "x",
                ),
            ),
        )
        assertEquals(false, actions.reviewResolve("s1", "c1"))
        assertNull(actions.reviewSubmit("s1"))
        assertNull(actions.getWalkthrough("s1"))
        assertNull(actions.walkthroughState("s1"))
        assertTrue(actions.display.listDisplays().isEmpty())
        assertTrue(actions.launcherAgents().isEmpty())
        assertNull(actions.finishReadiness("s1"))
        // The finish kickoff must report a REFUSAL rather than silently claiming acceptance.
        var accepted: Boolean? = null
        actions.kickoffFinish("s1", "merge", null, null, null) { accepted = it }
        assertEquals(false, accepted)
        assertTrue(actions.lspStatus.value.isEmpty())
        assertTrue(actions.sessions.value.isEmpty())
        assertTrue(actions.finishJobs.value.isEmpty())
        assertTrue(actions.display.displays.value.isEmpty())
    }

    @Test
    fun `the single-host builder exposes the store's own lsp flows`() = runComposeUiTest {
        val store = testHostStore()
        var actions: ShellActions? = null
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform(caps = NO_CAPS)) {
                actions = rememberShellActions(store)
            }
        }
        waitForIdle()
        val built = assertNotNull(actions)
        assertSame(store.lspStatus, built.lspStatus)
        assertSame(store.lspRpc, built.lspRpc)
    }

    /**
     * The view host does not only CALL the store, it COLLECTS it: a chat/terminal view resolves its
     * session, the chat panel reads its finish job, the display pane its stream. Those three flows
     * are part of the holder or a shared view host would have to be handed a store anyway.
     */
    @Test
    fun `the single-host builder carries the flows the view host collects`() = runComposeUiTest {
        val store = testHostStore()
        var actions: ShellActions? = null
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform(caps = NO_CAPS)) {
                actions = rememberShellActions(store)
            }
        }
        waitForIdle()
        val built = assertNotNull(actions)
        assertSame(store.sessions, built.sessions)
        assertSame(store.finishJobs, built.finishJobs)
        assertSame(store.displays, built.display.displays)
    }

    @Test
    fun `a changed session router does not rebuild the bundle`() = runComposeUiTest {
        val store = testHostStore()
        val built = mutableListOf<ShellActions>()
        var nonce by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform(caps = NO_CAPS)) {
                // A caller that captures shell state hands a FRESH lambda every recomposition;
                // keying the bundle on it would rebuild all ~35 lambdas each frame.
                val n = nonce
                built.add(rememberShellActions(store, appForSession = { _ -> store.also { n } }))
            }
        }
        waitForIdle()
        nonce = 1
        waitForIdle()
        assertTrue(built.size >= 2, "expected a recomposition")
        assertSame(built.first(), built.last())
    }

    @Test
    fun `the walkthrough holder is null on a host without the capability`() = runComposeUiTest {
        val store = testHostStore()
        var actions: ShellActions? = null
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform(caps = NO_CAPS)) {
                actions = rememberShellActions(store)
            }
        }
        waitForIdle()
        assertNull(assertNotNull(actions).walkthroughState("s1"))
    }

    @Test
    fun `the walkthrough holder is the store's own when the capability is on`() = runComposeUiTest {
        val store = testHostStore()
        var actions: ShellActions? = null
        setContent {
            CompositionLocalProvider(
                LocalPlatform provides FakePlatform(caps = NO_CAPS.copy(walkthrough = true)),
            ) {
                actions = rememberShellActions(store)
            }
        }
        waitForIdle()
        val held = assertNotNull(assertNotNull(actions).walkthroughState("s1"))
        assertEquals("s1", held.sessionId)
        // Same holder on a second read — one walkthrough per session, not one per call.
        assertSame(held, actions!!.walkthroughState("s1"))
    }

    @Test
    fun `session controls route to the store`() = runComposeUiTest {
        val store = testHostStore()
        var actions: ShellActions? = null
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform(caps = NO_CAPS)) {
                actions = rememberShellActions(store)
            }
        }
        waitForIdle()
        val built = assertNotNull(actions)
        // The store has no session by these ids, so the calls are no-ops — what is pinned is that
        // they reach the store at all rather than throwing on an unresolvable id.
        built.rename("s1", "renamed")
        built.setMute("s1", true)
        built.kill("s1")
        built.clearFinishJob("s1")
        built.lspOpen("s1", "server")
        built.lspStatusQuery("s1", "a.kt")
        built.lspRpcOut("s1", "server", "{}")
    }

}
