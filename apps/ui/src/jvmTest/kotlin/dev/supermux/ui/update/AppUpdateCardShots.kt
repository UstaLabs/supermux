package dev.supermux.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.platform.FakeAppUpdater
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.NO_CAPS
import dev.supermux.ui.session.SessionListFooter
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.update.ClientUpdateStatus
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Screenshots of the sidebar's update card on its footer rail, for eyeballing a design change.
 * A no-op unless SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*AppUpdateCardShots*'
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateCardShots {
    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val release = ClientUpdateStatus(
            currentVersion = "0.16.0",
            latestVersion = "0.16.1",
            updateAvailable = true,
            notesUrl = "https://example.test/notes",
            downloadUrl = "https://example.test/supermux-linux.deb",
            canInstall = true,
            highlights = listOf(
                "The running strip expands into per-agent lanes",
                "Sessions start on native Windows; relay spawn failures say why",
                "No console window per agent session",
                "Sidebar search",
            ),
        )
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) runComposeUiTest {
            val updater = FakeAppUpdater(release = release)
            setContent {
                CompositionLocalProvider(
                    LocalPlatform provides FakePlatform(caps = NO_CAPS.copy(appUpdate = true)).also { it.updates = updater },
                ) {
                    SupermuxTheme(appearance = theme) {
                        LaunchedEffect(updater) { updater.check() }
                        Column(Modifier.size(280.dp, 420.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Box(Modifier.weight(1f).fillMaxWidth())
                            AppUpdateCard()
                            SessionListFooter(
                                appearance = theme,
                                onToggleTheme = {},
                                onUsage = {},
                                onDevices = {},
                                onSettings = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "update-card-${theme.name.lowercase()}.png"))
        }
    }
}
