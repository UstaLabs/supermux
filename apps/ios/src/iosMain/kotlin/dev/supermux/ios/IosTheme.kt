package dev.supermux.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.rememberWindowWidthClass
import dev.supermux.ui.platform.NoticeOverlay
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import platform.GameController.GCKeyboard
import platform.GameController.GCMouse

/**
 * iOS's thin wrapper over `:ui`'s [HostTheme] — the third and last one, beside `AndroidTheme` and
 * `DesktopTheme`.
 *
 * The provider stack itself is shared; what iOS adds is exactly two things only it can answer, plus
 * the snackbar host:
 *
 *  - **Input mode.** `Touch` unless a hardware keyboard or a pointing device is attached — an iPad
 *    in a Magic Keyboard, or with a Bluetooth mouse. `GCKeyboard`/`GCMouse` (GameController) is the
 *    supported way to ask; UIKit has no "is there a keyboard" property, only per-event traits.
 *  - **Pointer availability.** Deliberately the mouse question ALONE, not the keyboard one: an iPad
 *    with a keyboard case and no trackpad is still a touch device, and sizing 28dp menu rows off it
 *    would put half-thumb targets under someone's finger.
 *  - **`NoticeOverlay`.** iOS has no Toast, so `Platform.notices` surfaces as a Compose snackbar at
 *    the root — the same host desktop uses, from the same `:ui` composable.
 *
 * No system-bar work, unlike Android: iOS has no bar-icon contrast to set (the status bar follows
 * `UIStatusBarStyle`, resolved from the app's own interface style), and the safe areas reach the
 * shared shell as `WindowInsets.safeDrawing` without anything being installed here.
 *
 * No width fallback is passed to [rememberWindowWidthClass]: unlike Android there is no
 * `Configuration` to consult, and `ComposeUIViewController` has its bounds by the time it measures,
 * so the Expanded-for-one-frame default never actually shows on a phone.
 */
@Composable
fun IosTheme(
    platform: IosPlatform,
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    uiPrefs: UiPrefs? = null,
    content: @Composable () -> Unit,
) {
    // `remember`ed rather than read on every recomposition: attaching a keyboard to an iPad is a
    // rare event, and GameController's device lists are not observable as Compose state — a poll
    // per frame would cost more than it could ever be worth. A hardware keyboard connected
    // mid-session is picked up on the next recomposition of this root.
    val pointerAvailable = remember { GCMouse.mice().isNotEmpty() }
    val inputMode = remember {
        if (pointerAvailable || GCKeyboard.coalescedKeyboard != null) InputMode.Pointer
        else InputMode.Touch
    }
    HostTheme(
        platform = platform,
        appearance = appearance,
        textScale = textScale,
        uiPrefs = uiPrefs,
        inputMode = inputMode,
        pointerAvailable = pointerAvailable,
        widthClass = rememberWindowWidthClass(),
    ) {
        NoticeOverlay(platform.notices, content)
    }
}
