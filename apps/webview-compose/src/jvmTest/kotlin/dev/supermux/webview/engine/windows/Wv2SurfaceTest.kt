package dev.supermux.webview.engine.windows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSurfaceHints
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class Wv2SurfaceTest {
    @AfterTest fun quiet() = WinPages.resetForTests()

    private fun rig(): WinRig = WinRig(TestScope()).also {
        it.engine.windows = FakeSurfaceWindows
        it.warm()
    }

    @Test fun aClickReachesThePageInTheSurfacesPixelsAndNothingUnderIt() = runComposeUiTest {
        val rig = rig()
        var under = 0
        setContent { Box(Modifier.clickable { under++ }) { rig.engine.Surface(Modifier.size(200.dp, 100.dp)) } }
        onRoot().performMouseInput { click(Offset(10f, 20f)) }
        waitForIdle()
        val mouse = rig.page.calls.filter { it.startsWith("mouse") }
        assertTrue(mouse.any { it.startsWith("mouse ${WinGlue.LEFT_DOWN} ") && it.endsWith(" 10 20") }, "$mouse")
        assertTrue(mouse.any { it.startsWith("mouse ${WinGlue.LEFT_UP} ") }, "$mouse")
        assertEquals(0, under, "L27: the surface consumes its pointer")
        rig.close()
    }

    @Test fun inAListTheWheelIsTheLists() = runComposeUiTest {
        val rig = rig()
        setContent {
            CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) {
                rig.engine.Surface(Modifier.size(200.dp, 100.dp))
            }
        }
        onRoot().performMouseInput { scroll(3f) }
        waitForIdle()
        assertTrue(rig.page.calls.none { it.startsWith("mouse ${WinGlue.WHEEL} ") }, "${rig.page.calls}")
        rig.close()
    }

    @Test fun movesApplyAtOnceAndSizesOnceSettled() {
        val rig = rig()
        rig.engine.onSurfacePlaced(null, 10, 20, 300, 200, now = 0L)
        assertEquals(listOf<Any>(1L, 2L, 10, 20, 300, 200, 1.5), rig.page.placed.last(), "the first place at once")
        rig.engine.onSurfacePlaced(null, 10, 60, 300, 200, now = 1_000L)
        assertEquals(60, rig.page.placed.last()[3], "a move at once")
        rig.engine.onSurfacePlaced(null, 10, 60, 340, 260, now = 2_000L)
        assertEquals(300, rig.page.placed.last()[4], "a size waits (C9)")
        rig.engine.settlePlacement(2_000L + WinDefaults.SIZE_SETTLE_NANOS)
        assertEquals(340, rig.page.placed.last()[4])
        rig.engine.onSurfaceAttached(false)
        assertTrue("unplace" in rig.page.calls, "no surface: the parking window is its parent")
        rig.close()
    }
}

/** Fixed handles: frame 1, canvas 2, scale 1.5 (a 150 % screen). */
internal object FakeSurfaceWindows : SurfaceWindows {
    override fun of(window: java.awt.Window?): SurfaceWindows.Placement = SurfaceWindows.Placement(1L, 2L, 1.5)
    override fun canvasComponent(window: java.awt.Window?): java.awt.Component? = null
}
