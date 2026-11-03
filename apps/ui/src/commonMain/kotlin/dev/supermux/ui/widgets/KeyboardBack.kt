// BACK closes the soft keyboard first — Android's rule, kept by the app itself (#30).
//
// The platform does not keep it for an app that handles BACK. Since Android 13 the keyboard's
// back callback is registered on the app's own window, at the same priority as the app's, and the
// window answers BACK with whichever callback was registered LAST
// (`WindowOnBackInvokedDispatcher.getTopCallback`). So an app callback registered after the
// keyboard came up takes BACK from it, and so does any app callback once the keyboard has dropped
// its own while staying on screen (`InputMethodService.doFinishInput` unregisters it without
// hiding the window). Either way the always-on chat-layer handler won: BACK left the chat with
// the keyboard still up, and the only way to close the keyboard was its own hide button.
//
// [KeyboardFirstBack] closes it from the app side instead. It is composed only while the keyboard
// is up, which makes it the most recently added handler and so the first one the dispatcher asks;
// one call in the shell gives every screen the rule. With the keyboard down the next BACK goes to
// the screen's own handler as before, predictive animation and all. When the keyboard's callback
// does win, the keyboard closes itself and this handler is simply never asked.
package dev.supermux.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Whether a soft keyboard is on screen that BACK has to close before it does anything else.
 *
 * Android provides it — the IME's visibility. It stays false on iOS on purpose: nothing there
 * closes the keyboard by going back, and the edge swipe must still pop a page with the keyboard
 * up. Desktop and the web have no soft keyboard of their own.
 */
val LocalSoftKeyboardTakesBack = compositionLocalOf { false }

/**
 * While [LocalSoftKeyboardTakesBack] is up, BACK hides the keyboard and does nothing else.
 *
 * Only hides it, the way the keyboard's own BACK does: the field keeps its focus, so a hardware
 * keyboard goes on typing into it and a tap brings the soft one straight back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardFirstBack() {
    if (!LocalSoftKeyboardTakesBack.current) return
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { keyboard?.hide() }
}
