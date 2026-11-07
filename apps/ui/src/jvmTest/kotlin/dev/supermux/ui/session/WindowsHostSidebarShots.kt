package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The Windows host's sidebar on a phone, for eyeballing: its folders filed under projects (each
 * row with its ⋮, and that menu open), and an old host that left them bare. A no-op unless
 * SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*WindowsHostSidebarShots*'
 */
@OptIn(ExperimentalTestApi::class)
class WindowsHostSidebarShots {
    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        // (file name, filed under projects?, open TunnelWho's ⋮?)
        val shots = listOf(
            Triple("windows-host-projects", true, false),
            Triple("windows-host-projects-menu", true, true),
            Triple("windows-host-unfiled", false, false),
        )
        for ((name, filed, menu) in shots) runDesktopComposeUiTest(width = 1000, height = 1500) {
            setContent {
                // A phone's density, so the picture reads like the phone it is compared with.
                CompositionLocalProvider(LocalDensity provides Density(2.5f)) {
                    Box(Modifier.size(400.dp, 600.dp)) { WindowsHostSidebar(filed = filed, collapsed = true) }
                }
            }
            waitForIdle()
            if (menu) {
                onNodeWithTag(ProjectTestIds.menu(WindowsHost.projectKey(1))).performClick()
                waitForIdle()
            }
            // An open menu is a popup, a second root; the list's root still draws it in its capture.
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
    }
}
