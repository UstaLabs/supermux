package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import kotlin.test.Test

/**
 * Screenshots of the editor's markdown preview, for eyeballing a design change. A no-op unless
 * MD_SHOTS_DIR is set:
 *   MD_SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*MarkdownPreviewShots*'
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownPreviewShots {
    private val sample = """
        # Release notes

        The editor now ships with **folding**, `Cmd-D` multi-select and a full LSP client.
        This paragraph is long enough to wrap, so the line height of a document is visible here.

        ## What changed

        - Bracket matching is syntax-aware
          - a `(` inside a string never matches code
          - nested lists keep their depth
        - Undo groups typing into bursts

        ### Checklist

        - [x] Cut over from CodeMirror
        - [ ] Ship the accessory bar on iPad

        1. Open a markdown file
        2. Toggle the preview
           - read it like a document

        > Folds are protected on every input path, not just hardware keys.

        ---

        #### Performance

        | metric | p95 |
        |---|--:|
        | keystroke | 6.8 ms |
        | scroll | 8 ms |

        ##### Footnote label

        ```kotlin
        fun main() = println("hello")
        ```
    """.trimIndent()

    @Test fun shots() {
        val dir = System.getenv("MD_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) {
            for (document in listOf(true, false)) runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalPlatform provides FakePlatform()) { SupermuxTheme(appearance = theme) {
                        Box(Modifier.size(900.dp, 1500.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.xl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MarkdownBody(sample, Modifier.markdownPreviewColumn(), document = document)
                            }
                        }
                    } }
                }
                waitForIdle()
                val name = "${theme.name.lowercase()}-${if (document) "preview" else "chat"}.png"
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
            }
        }
    }
}
