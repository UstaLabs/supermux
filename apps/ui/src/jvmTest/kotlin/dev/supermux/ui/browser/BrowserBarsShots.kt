package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.browser.DeviceMode
import dev.supermux.browser.DevicePresets
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Screenshots of the Browser tab's bars, menu and find bar, to compare with #122's prototype. A no-op unless SHOTS_DIR
 * is set: SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*BrowserBarsShots*'
 */
@OptIn(ExperimentalTestApi::class)
class BrowserBarsShots {
    private class Shot(val name: String, val theme: AppearanceMode, val width: Dp, val phone: Boolean, val menu: Boolean, val find: Boolean, val device: Boolean = false, val presets: Boolean = false)

    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val shots = listOf(
            Shot("desktop-bar-dark", AppearanceMode.DARK, 780.dp, phone = false, menu = false, find = false),
            Shot("desktop-menu-dark", AppearanceMode.DARK, 780.dp, phone = false, menu = true, find = false),
            Shot("desktop-find-dark", AppearanceMode.DARK, 780.dp, phone = false, menu = false, find = true),
            Shot("desktop-bar-light", AppearanceMode.LIGHT, 780.dp, phone = false, menu = true, find = false),
            Shot("desktop-device-dark", AppearanceMode.DARK, 780.dp, phone = false, menu = false, find = false, device = true),
            Shot("desktop-device-menu-dark", AppearanceMode.DARK, 780.dp, phone = false, menu = false, find = false, device = true, presets = true),
            Shot("desktop-device-light", AppearanceMode.LIGHT, 780.dp, phone = false, menu = false, find = false, device = true),
            Shot("phone-bar-dark", AppearanceMode.DARK, 390.dp, phone = true, menu = false, find = false),
            Shot("phone-menu-dark", AppearanceMode.DARK, 390.dp, phone = true, menu = true, find = false),
        )
        for (shot in shots) runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalPlatform provides FakePlatform(),
                    LocalWindowWidthClass provides if (shot.phone) WindowWidthClass.Compact else WindowWidthClass.Expanded,
                    LocalPointerAvailable provides !shot.phone,
                ) {
                    SupermuxTheme(appearance = shot.theme) {
                        Column(Modifier.size(shot.width, if (shot.phone) 640.dp else 420.dp).background(MaterialTheme.colorScheme.surface)) {
                            Bars(shot)
                            if (shot.device) {
                                Box(Modifier.fillMaxWidth().weight(1f)) {
                                    DeviceStage(DeviceMode.Default.withPreset(DevicePresets.byId("galaxy-a55")!!)) {
                                        Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA)))
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().height(300.dp))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            if (shot.presets) {
                onNodeWithTag("browser-device-preset").performClick()
                waitForIdle()
            }
            if (shot.menu) {
                onNodeWithTag("browser-menu").performClick()
                waitForIdle()
            }
            save(dir, shot.name)
        }
    }

    private fun ComposeUiTest.save(dir: File, name: String) {
        val roots = onAllNodes(isRoot())
        val n = roots.fetchSemanticsNodes().size
        ImageIO.write(roots[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        if (n > 1) ImageIO.write(roots[n - 1].captureToImage().toAwtImage(), "png", File(dir, "$name-popup.png"))
    }

    @Composable
    private fun Bars(shot: Shot) {
        val address = remember { FocusRequester() }
        BrowserToolbar(
            shown = "port 5173/checkout",
            full = "https://relay.test/p/5173/checkout",
            canGoBack = true,
            canGoForward = false,
            loading = false,
            zoomPercent = 110,
            address = address,
            onBack = {}, onForward = {}, onReload = {}, onSubmit = {}, onOpenExternally = {}, onFind = {}, onZoom = {}, onInspect = {},
            onScreenshot = {},
            preview = true,
            device = if (shot.phone) null else shot.device,
        )
        if (shot.device) DeviceBar(DeviceMode.Default.withPreset(DevicePresets.byId("galaxy-a55")!!), onChange = {}, onClose = {})
        if (shot.find) {
            val find = remember { FindState().apply { query = "pay"; said = "1 of 2" } }
            FindBar(find = find, onStep = {}, onClose = {})
        }
    }
}
