package dev.supermux.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.rememberWindowWidthClass
import dev.supermux.ui.platform.NoticeOverlay
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import kotlinx.browser.window

/**
 * Does a real pointing device drive this page?
 *
 * `(pointer: fine)` is the browser's own answer to exactly the question `InputMode` asks — it is
 * true for a mouse or trackpad and false for a touchscreen — so unlike iOS (GameController) and
 * Android (input-device classes) there is nothing to infer here. A hybrid laptop with a
 * touchscreen reports `fine`, which is the right call for hit targets: the mouse is present.
 */
private fun finePointer(): Boolean = window.matchMedia("(pointer: fine)").matches

/**
 * The browser's thin wrapper over `:ui`'s [HostTheme] — the fourth one, beside `AndroidTheme`,
 * `DesktopTheme` and `IosTheme`, and the closest to iOS's: input mode plus the snackbar host.
 *
 * [rememberWindowWidthClass] gets no fallback: `ComposeViewport` has the canvas's real size by the
 * time it measures, so the Expanded-for-one-frame default never shows.
 */
@Composable
fun WebTheme(
    platform: WebPlatform,
    appearance: AppearanceMode,
    textScale: Float,
    uiPrefs: UiPrefs,
    content: @Composable () -> Unit,
) {
    // `remember`ed, not polled: a media-query read per frame costs more than plugging a mouse in
    // mid-session is worth, and the next recomposition of this root picks it up anyway.
    val pointer = remember { finePointer() }
    HostTheme(
        platform = platform,
        appearance = appearance,
        textScale = textScale,
        uiPrefs = uiPrefs,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
        pointerAvailable = pointer,
        widthClass = rememberWindowWidthClass(),
    ) {
        // The browser has no Toast either, so `Platform.notices` surfaces as the same Compose
        // snackbar desktop and iOS use — over `platform.notices`, the ONE bus the platform holds.
        NoticeOverlay(platform.notices, content)
    }
}
