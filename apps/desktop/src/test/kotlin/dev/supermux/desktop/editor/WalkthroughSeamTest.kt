package dev.supermux.desktop.editor

import dev.supermux.desktop.DesktopWalkthroughSeam
import dev.supermux.desktop.testDeps
import dev.supermux.net.ReviewComment
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep
import dev.supermux.proto.ServerFrame
import dev.supermux.state.HostStore
import dev.supermux.ui.editor.WalkthroughState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walkthrough SEAM: `:shared`'s `HostStore` driving `:ui`'s [WalkthroughState] through
 * [DesktopWalkthroughSeam] (Android installs an identical `AndroidWalkthroughSeam`). It stays in
 * desktop rather than moving with the rest of `WalkthroughRegionTest` because building a
 * `HostStore` needs a ktor client, and `:ui` has no ktor on its test classpath.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkthroughSeamTest {

    @Test fun walkthrough_and_comment_frames_apply_once_via_the_seam() = runTest {
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            walkthroughSeam = DesktopWalkthroughSeam,
        )
        val walkthrough = Walkthrough(
            id = "w1", sessionId = "sess-1", title = "Tour", revision = 1,
            steps = listOf(WalkthroughStep(id = "st1", ord = 0, title = "First", path = "a.txt", anchorLine = 3)),
        )
        app.reduce(ServerFrame.WalkthroughUpdated("sess-1", walkthrough))
        app.reduce(
            ServerFrame.ReviewCommentFrame(
                "sess-1",
                ReviewComment(
                    id = "r1", parentId = "c1", repo = "", path = "a.txt", side = "RIGHT",
                    anchorLine = 3, body = "reply", author = "agent", status = "open",
                ),
            ),
        )
        assertEquals(1, app.walkthroughState<WalkthroughState>("sess-1").unreadReplies)
    }
}
