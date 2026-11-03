package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.files.GoToEntry
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Design screenshots of the composer's `@` menu. No-op unless MENTION_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class MentionShots {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "codex", session_branch = "mux/composer-at-mentions")

    private fun row(rel: String, dir: Boolean = false, nameHits: List<Int> = emptyList(), folderHits: List<Int> = emptyList()) = GoToEntry(
        absolutePath = "/home/u/proj/$rel",
        relativePath = rel,
        name = rel.substringAfterLast('/'),
        folder = rel.substringBeforeLast('/', ""),
        isDir = dir,
        nameHits = nameHits,
        folderHits = folderHits,
    )

    private fun files(lookup: MentionLookup): List<GoToEntry> = when (lookup) {
        MentionLookup.Folder("") -> listOf(
            row("apps", true), row("docs", true), row("packages", true), row("src", true), row("tests", true),
            row(".gitignore"), row("README.md"), row("package.json"), row("tsconfig.json"),
        )
        is MentionLookup.Search -> listOf(
            row("apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Composer.kt", nameHits = listOf(0, 1, 2, 3)),
            row("apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ComposerControls.kt", nameHits = listOf(0, 1, 2, 3)),
            row("apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ComposerFooter.kt", nameHits = listOf(0, 1, 2, 3)),
            row("apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ComposerKeyboard.kt", nameHits = listOf(0, 1, 2, 3)),
            row("apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ComposerTest.kt", nameHits = listOf(0, 1, 2, 3)),
            row("src/core/compose", true, nameHits = listOf(0, 1, 2, 3)),
        )
        else -> emptyList()
    }

    @Test
    fun shots() {
        val dir = System.getenv("MENTION_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val variants = listOf(Triple("desktop", 760, true), Triple("phone", 360, false))
        for (dark in listOf(false, true)) for ((name, w, pointer) in variants) for (typed in listOf("Look at @", "Look at @comp")) {
            runDesktopComposeUiTest(width = w, height = 520) {
                var draft by mutableStateOf("")
                val actions = ComposerActions(mentionFiles = { files(it) })
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
                                draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                                onSend = { _, _ -> }, onInterrupt = {},
                                onUpload = { _, _, _, _, _ -> "f" },
                                actions = actions,
                                tray = { ComposerFooter(session = session) },
                            )
                        }
                    }
                }
                onNodeWithTag("composer-input").performTextInput(typed)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("chat_mention_menu").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                val tag = (if (dark) "dark" else "light") + if (typed.endsWith("@")) "-folder" else "-search"
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$tag.png"))
            }
        }
    }
}
