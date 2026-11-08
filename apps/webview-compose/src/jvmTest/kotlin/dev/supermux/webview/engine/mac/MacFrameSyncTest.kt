package dev.supermux.webview.engine.mac

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.SkikoRenderDelegate
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Frame sync's capability check: used only where all it assumes of skiko holds (the glue checks the rest). */
class MacFrameSyncTest {
    private val all = MacFrameSync.Facts(
        skikoVersion = MacFrameSync.PINNED_SKIKO,
        renderApi = "METAL",
        recordingsMarked = true,
        switchedOff = false,
    )

    @Test fun usableWhereEverythingItAssumesHolds() {
        assertTrue(MacFrameSync.usable(all))
    }

    @Test fun anotherSkikoIsNotTrusted() {
        assertFalse(MacFrameSync.usable(all.copy(skikoVersion = "0.150.3")))
        assertFalse(MacFrameSync.usable(all.copy(skikoVersion = "unknown")))
    }

    @Test fun onlyMetalPresentsThroughTheLayerItHooks() {
        for (api in listOf("OPENGL", "SOFTWARE_FAST", "SOFTWARE_COMPAT", "UNKNOWN", null)) {
            assertFalse(MacFrameSync.usable(all.copy(renderApi = api)), "$api")
        }
    }

    /** Without the recordings marked, a frame couldn't tell its own placements from the next layout's. */
    @Test fun unmarkedRecordingsAreNotSynced() {
        assertFalse(MacFrameSync.usable(all.copy(recordingsMarked = false)))
    }

    @Test fun switchedOffIsOff() {
        assertFalse(MacFrameSync.usable(all.copy(switchedOff = true)))
    }

    /** A tripwire: the skiko this library is built with is the one whose internals the glue pins. */
    @Test fun theSkikoOnTheClasspathIsThePinnedOne() {
        val facts = MacFrameSync.factsOf(null)
        assertEquals(MacFrameSync.PINNED_SKIKO, facts.skikoVersion, "skiko moved: check MacFrameSync's and WVFrameSync.m's pins")
        assertNull(facts.renderApi, "no Compose window, no renderer")
        assertFalse(facts.recordingsMarked, "no window, no recordings")
    }

    @Test fun thePropertyAndTheChecksSwitchTurnItOff() {
        val before = System.getProperty(MacFrameSync.PROPERTY)
        try {
            System.setProperty(MacFrameSync.PROPERTY, "off")
            assertTrue(MacFrameSync.factsOf(null).switchedOff)
            System.setProperty(MacFrameSync.PROPERTY, "on")
            assertFalse(MacFrameSync.factsOf(null).switchedOff)
        } finally {
            if (before == null) System.clearProperty(MacFrameSync.PROPERTY) else System.setProperty(MacFrameSync.PROPERTY, before)
        }
        try {
            MacFrameSync.enabled = false
            assertTrue(MacFrameSync.factsOf(null).switchedOff)
        } finally {
            MacFrameSync.enabled = true
        }
        assertFalse(MacFrameSync.factsOf(null).switchedOff)
    }

    @Test fun aComponentTreeWithoutSkiaLayerHasNone() {
        val root = JPanel().apply { add(JPanel().apply { add(JPanel()) }) }
        assertNull(MacFrameSync.skiaLayerOf(root))
    }

    /** The marker stands in for Compose's delegate: every frame is Compose's own, and only then marked. */
    @Test fun theMarkerRecordsThroughComposesDelegate() {
        val seen = mutableListOf<String>()
        val inner = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                seen += "$width x $height at $nanoTime"
            }
        }
        val marker = RecordingMarker(inner, window = 0L)
        assertSame(inner, marker.inner)
        val surface = Surface.makeRasterN32Premul(4, 3)
        marker.onRender(surface.canvas, 4, 3, 42L)
        surface.close()
        assertEquals(listOf("4 x 3 at 42"), seen)
    }
}
