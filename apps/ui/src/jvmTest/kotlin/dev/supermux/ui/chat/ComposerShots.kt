package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.PermissionModeInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Design screenshots of the chat composer + footer. No-op unless COMPOSER_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class ComposerShots {
    private val models = ModelsResponse(
        agent = "claude",
        current = "opus",
        models = listOf(ModelInfo("opus", "Opus 4.7"), ModelInfo("sonnet", "Sonnet 4.6")),
    )
    private val reasoning = ReasoningResponse(
        agent = "claude", current = "high",
        levels = listOf(ReasoningLevel("low"), ReasoningLevel("medium"), ReasoningLevel("high"), ReasoningLevel("max")),
        visible = true,
    )
    private val perms = listOf(
        PermissionModeInfo("default", "Ask", "Ask before edits", default = true),
        PermissionModeInfo("bypass", "Bypass", "Never ask"),
    )
    private val session = SessionInfo(
        id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude",
        session_branch = "mux/supermux-101",
    )

    @Test
    fun shots() {
        val dir = System.getenv("COMPOSER_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val variants = listOf(
            Triple("desktop", 760, true),
            Triple("phone", 360, false),
        )
        for (dark in listOf(false, true)) for ((name, w, pointer) in variants) for (draft in listOf("", "Refactor the composer so it fits on a phone and keeps every control reachable", "menu")) {
            runDesktopComposeUiTest(width = w, height = if (draft == "menu") 640 else 260) {
                setPlatformContent(
                    pointer = pointer,
                    widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
                    inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
                ) {
                    SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = if (pointer) 16.dp else 8.dp, vertical = 12.dp),
                        ) {
                            Spacer(Modifier.weight(1f))
                            Composer(
                                draft = if (draft == "menu") "" else draft, onDraftChange = {}, sending = false, agentWorking = false,
                                onSend = { _, _ -> }, onInterrupt = {},
                                onUpload = { _, _, _, _, _ -> "f" },
                                onTranscribeAudio = { _, _, _ -> "" },
                                models = models, reasoning = reasoning, sessionAgent = "claude",
                                permissionModes = perms, sessionPermissionMode = "default",
                                fastMode = false,
                                openModelPickerNonce = if (draft == "menu") 1L else 0L,
                                tray = { ComposerFooter(session = session) },
                            )
                        }
                    }
                }
                waitForIdle()
                val tag = (if (dark) "dark" else "light") + when (draft) { "" -> ""; "menu" -> "-menu"; else -> "-text" }
                val image = if (draft == "menu") {
                    // Popups are separate roots; the menu/sheet is the last one.
                    onAllNodes(isRoot()).fetchSemanticsNodes().size.let { n -> onAllNodes(isRoot())[n - 1] }.captureToImage()
                } else {
                    onRoot().captureToImage()
                }
                ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-$tag.png"))
            }
        }
    }
}
