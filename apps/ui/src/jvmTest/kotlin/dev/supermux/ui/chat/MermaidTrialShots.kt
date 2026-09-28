package dev.supermux.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** TRIAL: screenshots of ```mermaid fences through MarkdownBody. No-op unless MERMAID_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class MermaidTrialShots {
    private val cases = linkedMapOf(
        "flowchart" to """
            flowchart TD
              U[User message] --> B{Broker}
              B -->|telegram| T[Telegram bot]
              B -->|web| W[Web PWA]
              subgraph Sessions
                S1[claude session]
                S2[codex session]
              end
              B --> S1 & S2
              S1 -- reply --> B
              S2 -. idle .-> B
              W --> DB[(SQLite)]
        """.trimIndent(),
        "sequence" to """
            sequenceDiagram
              participant C as Client
              participant B as Broker
              participant A as Agent
              C->>B: send message
              B->>A: route to session
              activate A
              A-->>B: reply
              deactivate A
              B-->>C: push notification
              Note over B,A: zmx keeps the PTY alive
              alt session idle
                B->>A: wake
              else busy
                B->>C: queued
              end
        """.trimIndent(),
        "state" to """
            stateDiagram-v2
              [*] --> Idle
              Idle --> Running: message
              Running --> Waiting: tool call
              Waiting --> Running: result
              Running --> Idle: reply sent
              Running --> [*]: killed
        """.trimIndent(),
        "er" to """
            erDiagram
              USER ||--o{ SESSION : owns
              SESSION ||--|{ MESSAGE : contains
              SESSION { string id string workdir }
              MESSAGE { string id string text }
        """.trimIndent(),
        "gantt" to """
            gantt
              title Mermaid rollout
              dateFormat YYYY-MM-DD
              section Trial
              Try cmp-mermaid :a1, 2026-09-28, 2d
              section Build
              Chat integration :after a1, 3d
              Fullscreen viewer :2026-10-03, 2d
        """.trimIndent(),
        "pie" to """
            pie title Diagrams agents write
              "flowchart" : 62
              "sequence" : 21
              "state" : 9
              "other" : 8
        """.trimIndent(),
        "broken" to "flowchart TD\n  A --> \n  B -->> [[[",
    )

    @Test
    fun shots() {
        val dir = System.getenv("MERMAID_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (dark in listOf(false, true)) for ((name, src) in cases) {
            val t0 = System.nanoTime()
            runDesktopComposeUiTest(width = 560, height = 720) {
                setContent {
                    androidx.compose.runtime.CompositionLocalProvider(dev.supermux.ui.platform.LocalPlatform provides dev.supermux.ui.platform.FakePlatform()) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Surface {
                            MarkdownBody(
                                text = "Here is the **$name** diagram:\n\n```mermaid\n$src\n```\n\nDone.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    }
                }
                waitForIdle(); Thread.sleep(1500); waitForIdle()
                val suffix = if (dark) "dark" else "light"
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-$suffix.png"))
            }
            println("MERMAID_SHOT $name dark=$dark ${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }
}
