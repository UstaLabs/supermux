package dev.supermux.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The single entry point Swift calls: a `UIViewController` hosting Compose.
 *
 * Swift's job shrinks to building this, putting it inside a `UINavigationController` with a hidden
 * bar (which is what delivers the interactive swipe-back to `PredictiveBackHandler`), and handing
 * over an [IosBridge] for the things only Swift can do.
 *
 * H1 mounts a placeholder on purpose. This cluster's whole claim is that `:ui` COMPILES and
 * `SupermuxKit` LINKS for iOS — mounting `SupermuxApp` here would also drag in a store, a Keychain
 * token store, an `NSUserDefaults` settings store and safe-area insets, none of which exist yet, and
 * a link failure would then be indistinguishable from a wiring failure. H2 replaces the body with
 * the real root and this signature does not change.
 */
fun MainViewController(bridge: IosBridge = NoopIosBridge): UIViewController =
    ComposeUIViewController {
        // The platform is built here rather than by the caller so Swift never has to name a Kotlin
        // type it does not otherwise need; H2 provides it through LocalPlatform around the app root.
        // Constructing it now is also the cheapest possible proof at launch that the whole `:ui`
        // seam graph linked: IosPlatform touches the terminal, editor, updater and notification
        // factories, so a missing symbol in any of them fails HERE rather than three screens in.
        val platform = IosPlatform(bridge)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("SupermuxKit linked (camera: ${platform.caps.camera})")
        }
    }
