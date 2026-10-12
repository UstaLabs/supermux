package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.platform.FakeMic
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Screenshots of the composer while dictating (the recording pill above the field), for
 * eyeballing a design change. A no-op unless SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*DictationPillShots*'
 */
@OptIn(ExperimentalTestApi::class)
class DictationPillShots {
    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) runComposeUiTest {
            setContent {
                var draft by mutableStateOf("Note for the team: meet on Tuesday at 3pm.")
                CompositionLocalProvider(
                    LocalPlatform provides FakePlatform().apply { mic = FakeMic() },
                    LocalPointerAvailable provides false,
                ) {
                    SupermuxTheme(appearance = theme) {
                        Box(Modifier.size(400.dp, 300.dp).background(MaterialTheme.colorScheme.background)) {
                            Column(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.size(120.dp))
                                Composer(
                                    draft = draft, onDraftChange = { draft = it }, sending = false, agentWorking = false,
                                    onSend = { _, _ -> }, onInterrupt = {},
                                    onTranscribeAudio = { _, _, _ -> "x" },
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("composer-mic").performClick()
            mainClock.advanceTimeBy(7_000)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "pill-${theme.name.lowercase()}.png"))
        }
    }
}
