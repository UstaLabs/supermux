package dev.supermux.ui.theme

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #76: the OS's reduced-motion answer reaches the screens under the shared theme (Android, iOS, web). */
@OptIn(ExperimentalTestApi::class)
class HostThemeReduceMotionTest {
    @Test fun theOsAnswerReachesTheScreens() = runComposeUiTest {
        var seen: Boolean? = null
        setContent { HostTheme(platform = FakePlatform(), reduceMotion = true) { seen = LocalReduceMotion.current } }
        waitForIdle()
        assertEquals(true, seen)
    }

    @Test fun itIsOffUnlessThePlatformSaysSo() = runComposeUiTest {
        var seen: Boolean? = null
        setContent { HostTheme(platform = FakePlatform()) { seen = LocalReduceMotion.current } }
        waitForIdle()
        assertEquals(false, seen)
    }
}
