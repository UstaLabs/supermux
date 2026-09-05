package dev.supermux.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.desktop.platform.DesktopPlatform
import dev.supermux.ui.platform.LocalPlatform

/**
 * `setContent` with the real [DesktopPlatform] installed on `LocalPlatform`.
 *
 * The timeline reads the platform for read-aloud (`Platform.tts`) and the attachment chip's
 * save/open (`Platform.files`), and `LocalPlatform` is deliberately static-with-no-default — an
 * unprovided read is a wiring bug in an entry point, so it throws rather than silently no-op'ing.
 * A UI test that renders a message therefore has to provide one, exactly as `DesktopTheme` does in
 * production. The real platform is safe here: nothing in these suites clicks the seams.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setPlatformContent(content: @Composable () -> Unit) = setContent {
    CompositionLocalProvider(LocalPlatform provides DesktopPlatform()) {
        content()
    }
}
