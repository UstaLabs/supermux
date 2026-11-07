package dev.supermux.webview.runtime

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OverlayRegistryTest {
    private fun overlay(left: Float) = OverlayShape(Rect(left, 0f, left + 10f, 10f))
    private fun scrim(color: Color) = ScrimShape(Rect(0f, 0f, 100f, 100f), color)

    @Test fun overlaysAreKeptByKeyInTheOrderTheyRegistered() {
        val registry = OverlayRegistry()
        val a = Any()
        val b = Any()
        val c = Any()
        registry.setOverlay(a, overlay(0f))
        registry.setOverlay(b, overlay(10f))
        registry.setOverlay(c, overlay(20f))
        registry.setOverlay(b, overlay(15f))
        assertEquals(listOf(overlay(0f), overlay(15f), overlay(20f)), registry.shapes)
        registry.setOverlay(a, null)
        assertEquals(listOf(overlay(15f), overlay(20f)), registry.shapes)
        // Shown again, it registers again: last.
        registry.setOverlay(a, overlay(0f))
        assertEquals(listOf(overlay(15f), overlay(20f), overlay(0f)), registry.shapes)
    }

    @Test fun scrimsKeepTheirRegistrationOrderWhileTheyChange() {
        val registry = OverlayRegistry()
        val dialog = Any()
        val nested = Any()
        registry.setScrim(dialog, scrim(Color.Black))
        registry.setScrim(nested, scrim(Color.Red))
        // A scrim that changes color or moves keeps its place in the stack.
        registry.setScrim(dialog, scrim(Color.Blue))
        assertEquals(listOf(scrim(Color.Blue), scrim(Color.Red)), registry.scrims)
        // One that goes and comes back is on top.
        registry.setScrim(dialog, null)
        registry.setScrim(dialog, scrim(Color.Black))
        assertEquals(listOf(scrim(Color.Red), scrim(Color.Black)), registry.scrims)
    }

    @Test fun keysAreComparedByEquality() {
        val registry = OverlayRegistry()
        registry.setOverlay("menu", overlay(0f))
        registry.setOverlay("menu", overlay(5f))
        assertEquals(listOf(overlay(5f)), registry.shapes)
        registry.setOverlay("menu", null)
        assertTrue(registry.shapes.isEmpty())
    }

    @Test fun overlaysAndScrimsAreKeptApart() {
        val registry = OverlayRegistry()
        val key = Any()
        registry.setOverlay(key, overlay(0f))
        registry.setScrim(key, scrim(Color.Black))
        registry.setScrim(key, null)
        assertEquals(listOf(overlay(0f)), registry.shapes)
        assertTrue(registry.scrims.isEmpty())
    }

    @Test fun aReadCopiesNothing() {
        val registry = OverlayRegistry()
        registry.setOverlay(Any(), overlay(0f))
        registry.setScrim(Any(), scrim(Color.Black))
        assertSame(registry.shapes, registry.shapes)
        assertSame(registry.scrims, registry.scrims)
    }

    @Test fun writingWhatIsAlreadyThereChangesNoState() {
        val registry = OverlayRegistry()
        val menu = Any()
        val modal = Any()
        registry.setOverlay(menu, overlay(0f))
        registry.setScrim(modal, scrim(Color.Black))
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter {
                registry.setOverlay(menu, overlay(0f))
                registry.setScrim(modal, scrim(Color.Black))
                registry.setOverlay(Any(), null)
                registry.setScrim(Any(), null)
            }
            assertFalse(snapshot.hasPendingChanges())
        } finally {
            snapshot.dispose()
        }
    }

    @Test fun readersHearOfChangesAndOnlyOfChanges() {
        val registry = OverlayRegistry()
        val read = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { read += it }) {
            registry.shapes
            registry.scrims
        }
        var heard = 0
        val observer = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in read }) heard++ }
        try {
            val key = Any()
            registry.setOverlay(key, overlay(0f))
            Snapshot.sendApplyNotifications()
            assertEquals(1, heard)
            // A layout pass that moves nothing wakes no reader.
            registry.setOverlay(key, overlay(0f))
            Snapshot.sendApplyNotifications()
            assertEquals(1, heard)
            registry.setScrim(key, scrim(Color.Black))
            Snapshot.sendApplyNotifications()
            assertEquals(2, heard)
        } finally {
            observer.dispose()
        }
    }

    @Test fun aChangeMadeInASnapshotShowsOnceItIsApplied() {
        val registry = OverlayRegistry()
        val key = Any()
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter {
                registry.setOverlay(key, overlay(0f))
                registry.setScrim(key, scrim(Color.Black))
                assertEquals(listOf(overlay(0f)), registry.shapes)
            }
            assertTrue(registry.shapes.isEmpty())
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        assertEquals(listOf(overlay(0f)), registry.shapes)
        assertEquals(listOf(scrim(Color.Black)), registry.scrims)
    }

    @Test fun aDiscardedSnapshotLeavesNothingBehind() {
        val registry = OverlayRegistry()
        val kept = Any()
        val discarded = Any()
        registry.setOverlay(kept, overlay(0f))
        val snapshot = Snapshot.takeMutableSnapshot()
        snapshot.enter {
            registry.setOverlay(discarded, overlay(10f))
            registry.setOverlay(kept, null)
        }
        snapshot.dispose()
        assertEquals(listOf(overlay(0f)), registry.shapes)
        // No bookkeeping outside snapshot state went out of step: the keys still behave.
        registry.setOverlay(discarded, overlay(20f))
        registry.setOverlay(kept, null)
        assertEquals(listOf(overlay(20f)), registry.shapes)
    }
}
