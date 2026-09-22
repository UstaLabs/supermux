// One extra window on iPad: the Compose content of a scene of the `extra` WindowGroup. The screen
// itself is the shared `ExtraWindowHost` (Android's extra activity draws the same one).
package dev.supermux.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.supermux.settings.NSUserDefaultsSettingsStore
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.shell.windows.ExtraWindowHost
import dev.supermux.ui.shell.windows.ExtraWindows
import dev.supermux.ui.shell.windows.decodeClaim
import dev.supermux.ui.shell.windows.encode
import dev.supermux.ui.theme.AppearanceMode
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/**
 * The view controller for the extra window whose claim is [claim] (the `WindowGroup` value,
 * `PersistedWindowHost.encode()`d). [onClaim] hands back the claim whenever it changes, so the
 * scene's stored value — what iPadOS restores the window with — stays current.
 *
 * [bridge] is THIS scene's: sheets present from this window, and [IosBridge.closeWindow] closes it.
 */
@OptIn(ExperimentalNativeApi::class)
fun ExtraWindowViewController(
    bridge: IosBridge,
    claim: String,
    onClaim: (String) -> Unit,
): UIViewController {
    val parsed = decodeClaim(claim)
    // A restored window (a new process, an empty registry) parks its claim until the main window
    // composes the workspace and the registry takes it back.
    parsed?.let { ExtraWindows.adopt(IosWindows.shellWindows, it) }
    val uiPrefs = UiPrefs(NSUserDefaultsSettingsStore(NSUserDefaults.standardUserDefaults))
    // Blocking for the same reason `MainViewController` blocks: no first frame in the wrong theme.
    val (appearanceSeed, textScaleSeed) = runBlocking {
        uiPrefs.appearance(AppearanceMode.SYSTEM).first() to uiPrefs.textScale.first()
    }
    var lastClaim = claim
    val controller = IosWindowHostController(bridge)
    // Weak: the controller owns this composition, so a strong reference back would be a cycle
    // across the Objective-C boundary, which the Kotlin collector cannot break.
    var self: WeakReference<UIViewController>? = null

    return ComposeUIViewController {
        val platform = remember { IosPlatform(bridge) }
        val appearance by uiPrefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
        val textScale by uiPrefs.textScale.collectAsState(textScaleSeed)
        IosTheme(platform = platform, appearance = appearance, textScale = textScale, uiPrefs = uiPrefs) {
            if (parsed == null) {
                // Not a claim at all (a value from some other build): nothing to show.
                LaunchedEffect(Unit) { bridge.closeWindow() }
                return@IosTheme
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ExtraWindowHost(
                    hostId = parsed.id,
                    windows = IosWindows.shellWindows,
                    mainUi = IosWindows.mainUi,
                    onClaim = { now ->
                        val encoded = now.encode()
                        if (encoded != lastClaim) {
                            lastClaim = encoded
                            onClaim(encoded)
                        }
                    },
                    onClaimGone = bridge::closeWindow,
                    // The scene's title is what App Exposé and the window switcher show.
                    onTitle = { title -> self?.get()?.view?.window?.windowScene?.title = title },
                    onOpenMain = bridge::openMainWindow,
                    onOpened = controller::open,
                )
            }
        }
    }.also { self = WeakReference(it) }
}

/**
 * The extra window whose claim is [claim] was closed: its views go back to the main window.
 * Swift calls this when the scene's content goes away.
 */
fun releaseExtraWindow(claim: String) {
    decodeClaim(claim)?.let { ExtraWindows.close(IosWindows.shellWindows, it.id) }
}
