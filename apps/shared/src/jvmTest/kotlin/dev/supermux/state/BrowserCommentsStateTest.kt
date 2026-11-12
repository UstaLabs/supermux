package dev.supermux.state

import dev.supermux.net.BrowserCommentDto
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #122 phase 4: a host keeps its Browser tab comments by id, as the broker stored them and as the agent answers them. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserCommentsStateTest {
    private fun comment(status: String, note: String? = null) = BrowserCommentDto(
        id = "c1", sessionId = "s1", batchId = "b1", number = 1, selector = "button.buy", comment = "cut off",
        status = status, note = note, createdAt = "t", updatedAt = "t",
    )

    @Test fun aSentCommentIsKeptAndTheAgentsAnswerReplacesIt() = runTest(UnconfinedTestDispatcher()) {
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = {})
        app.rememberBrowserComments(listOf(comment("sent")))
        assertEquals("sent", app.browserComments.value["c1"]?.status)
        app.reduce(ServerFrame.BrowserCommentFrame("s1", comment("done", "padding fixed")))
        assertEquals("done", app.browserComments.value["c1"]?.status)
        assertEquals("padding fixed", app.browserComments.value["c1"]?.note)
        app.close()
    }
}
