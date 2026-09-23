package dev.supermux.desktop.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.RemoteRepo
import dev.supermux.session.ProjectActivity
import dev.supermux.ui.session.LauncherActions
import dev.supermux.ui.session.ProjectPicker
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.widgets.MenuStyle
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Design screenshots of the project picker in its main states, over sample projects. Test-source
 * only, and a no-op unless PICKER_SHOTS_DIR is set (PICKER_SHOTS_THEME=dark for the dark theme):
 *
 *   PICKER_SHOTS_DIR=/tmp/shots ./gradlew :desktop:test --tests '*PickerShots*'
 */
@OptIn(ExperimentalTestApi::class)
class PickerShots {
    private val home = "/home/u"
    private val names = listOf(
        "projects/supermux", "projects/kurbanhane", "work/greenmate", "projects/openwhisper",
        "projects/simplesync", "work/tekbir", "projects/flight-track", "projects/muhasebe",
        "projects/supercomment", "work/aprar", "projects/balkanlar-talebe-rapor",
    )
    private val projects = names.map { "$home/$it" }
    private val states = listOf(
        "1-empty" to "", "2-fuzzy-smx" to "smx", "3-prefix-su" to "su", "4-path" to "~/pro",
        "5-new-name" to "new-thing", "6-remote-term" to "term", "7-no-match" to "zzzq",
    )

    @Test fun shots() {
        val dir = System.getenv("PICKER_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val theme = if (System.getenv("PICKER_SHOTS_THEME") == "dark") AppearanceMode.DARK else AppearanceMode.LIGHT
        val now = Clock.System.now().toEpochMilliseconds()
        val minutesAgo = listOf(2, 38, 60, 180, 1_560, 3_000, 5_760, 8_640, 12_960, 21_600, 50_400)
        val activity = projects.mapIndexed { i, p ->
            p to ProjectActivity(if (i < 3) 1 else 0, now - minutesAgo[i] * 60_000L)
        }.toMap()
        val repos = listOf("UstaLabs/supermux", "UstaLabs/supercomment", "UstaLabs/terminal-core")
        val actions = LauncherActions(
            listForges = { listOf(ForgeConnection(id = "gh", host = "github.com", account = ForgeAccount(login = "UstaLabs"))) },
            searchForge = { q ->
                ForgeSearchResponse(
                    repos = repos.filter { it.contains(q, ignoreCase = true) }.map {
                        RemoteRepo(
                            connectionId = "gh", host = "github.com", owner = it.substringBefore('/'),
                            name = it.substringAfter('/'), fullName = it, private = true,
                        )
                    },
                )
            },
        )
        for ((name, query) in states) {
            runDesktopComposeUiTest(width = 520, height = 900) {
                setContent {
                    DesktopTheme(appearance = theme) {
                        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                            Surface(
                                shape = MenuStyle.Shape,
                                color = MenuStyle.containerColor,
                                border = MenuStyle.border,
                                shadowElevation = MenuStyle.ShadowElevation,
                            ) {
                                ProjectPicker(
                                    expanded = true,
                                    current = projects.first(),
                                    projects = projects,
                                    home = home,
                                    actions = actions,
                                    onPick = {},
                                    onDismiss = {},
                                    activity = activity,
                                    useDropdownMenu = false,
                                )
                            }
                        }
                    }
                }
                if (query.isNotEmpty()) onNodeWithTag("launcher_project_search").performTextInput(query)
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
            }
        }
    }
}
