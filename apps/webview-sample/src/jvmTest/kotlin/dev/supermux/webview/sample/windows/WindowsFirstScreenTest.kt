package dev.supermux.webview.sample.windows

import dev.supermux.webview.sample.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsFirstScreenTest {
    @Test fun theWindowsSampleOpensOnTheChatLikeTheOtherPlatforms() {
        assertEquals(Screen.Chat, windowsFirstScreen(out = null, screenProperty = null))
    }

    @Test fun aScreenPropertyPicksTheFirstScreenWhateverItsCase() {
        assertEquals(Screen.PerfLab, windowsFirstScreen(out = null, screenProperty = "perflab"))
        assertEquals(Screen.Chat, windowsFirstScreen(out = null, screenProperty = "no-such-screen"))
    }

    @Test fun anUnattendedLabRunStillOpensOnThePerfLab() {
        assertEquals(Screen.PerfLab, windowsFirstScreen(out = File("out"), screenProperty = "chat"))
    }
}
