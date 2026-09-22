package dev.supermux.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.rememberWindowWidthClass
import dev.supermux.ui.platform.NoticeOverlay
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import platform.GameController.GCMouse

/**
 * iOS's thin wrapper over `:ui`'s [HostTheme] — the third and last one, beside `AndroidTheme` and
 * `DesktopTheme`.
 *
 * The provider stack itself is shared; what iOS adds is exactly two things only it can answer, plus
 * the snackbar host:
 *
 *  - **Input mode and pointer availability.** Both answer the same question on iOS — is a real
 *    pointing device driving this? — via `GCMouse` (GameController; UIKit exposes no such
 *    property, only per-event traits). See the note at the call below for why the keyboard does
 *    NOT count here even though it does on Android.
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
    // `remember`ed rather than read on every recomposition: attaching a pointer to an iPad is a
    // rare event, and GameController's device lists are not observable as Compose state — a poll
    // per frame would cost more than it could ever be worth. A device connected mid-session is
    // picked up on the next recomposition of this root.
    val pointerAvailable = remember { GCMouse.mice().isNotEmpty() }
    // A POINTING device alone decides the mode on iOS — a hardware keyboard deliberately does not,
    // which is where this diverges from Android's `inputModeFor` (there, `KEYBOARD_QWERTY` also
    // means Pointer).
    //
    // Two reasons, and the first one is a bug this cost:
    //
    //  1. `GCKeyboard.coalescedKeyboard` is non-null on every SIMULATOR, because the Simulator
    //     forwards the Mac's own keyboard by default. Counting it put an iPhone into Pointer mode,
    //     which switches the session list to its pointer reorder affordances — and a tap on a
    //     session row then registered as a press that never became a click. The list was
    //     unusable, and nothing about the device was actually "pointer".
    //  2. It is the same lesson Android already learned as "a built-in touchpad is not a pointer"
    //     (`isPointerDevice`): what matters is whether a real pointing device is driving the UI,
    //     not whether some keyboard-shaped thing is attached. A keyboard does not make a 28dp hit
    //     target reachable by a thumb.
    //
    // An iPad in a Magic Keyboard still resolves to Pointer, because that keyboard HAS a trackpad
    // and so reports a `GCMouse`. A keyboard-only folio stays Touch, which is the right answer for
    // hit targets and costs only the keyboard-affordance hints.
    val inputMode = remember { if (pointerAvailable) InputMode.Pointer else InputMode.Touch }
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
