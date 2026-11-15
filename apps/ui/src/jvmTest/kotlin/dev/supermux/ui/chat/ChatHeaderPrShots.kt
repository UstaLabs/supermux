package dev.supermux.ui.chat

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SessionPrDto
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** #152 C4: the started chat's header with its PR chip, against the prototype's 07-started. No-op unless CHAT_HEADER_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderPrShots {
    @Test
    fun shots() {
        val dir = System.getenv("CHAT_HEADER_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val base = SessionInfo(id = "s1", name = "Fix login redirect loop", workdir = "/Users/ahmet/code/storefront", agent = "claude", status = "working")
        val scenes = listOf(
            "pr" to base.copy(source = "pr", pr = SessionPrDto("github", 123, "https://github.com/acme/storefront/pull/123"), push_remote = "origin"),
            "mr-review" to base.copy(source = "pr", pr = SessionPrDto("gitlab", 7, "https://gitlab.com/acme/storefront/-/merge_requests/7")),
        )
        for (dark in listOf(true, false)) for ((scene, session) in scenes) {
            runDesktopComposeUiTest(width = 1040, height = 200) {
                setPlatformContent(
                    platform = FakePlatform(),
                    pointer = true,
                    widthClass = WindowWidthClass.Expanded,
                    inputMode = InputMode.Pointer,
                ) {
                    SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                        val app = remember { testHostStore() }
                        ChatPanel(
                            session = session,
                            state = rememberChatState(app, session.id),
                            actions = rememberChatActions(app, session),
                            draft = "",
                            onDraftChange = {},
                            showHeader = true,
                        )
                    }
                }
                waitForIdle()
                mainClock.advanceTimeBy(400)
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "header-$scene-${if (dark) "dark" else "light"}.png"))
            }
        }
    }
}
