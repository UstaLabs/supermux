package dev.supermux.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateNotifierTest {
    @Test
    fun progressPercent_knownLength() {
        assertEquals(0, AppUpdateNotifier.progressPercent(0, 1_000))
        assertEquals(50, AppUpdateNotifier.progressPercent(500, 1_000))
        assertEquals(100, AppUpdateNotifier.progressPercent(1_000, 1_000))
        assertEquals(100, AppUpdateNotifier.progressPercent(1_200, 1_000))
    }

    @Test
    fun progressPercent_unknownLength() {
        assertNull(AppUpdateNotifier.progressPercent(100, null))
        assertNull(AppUpdateNotifier.progressPercent(100, 0))
        assertNull(AppUpdateNotifier.progressPercent(100, -1))
    }

    @Test
    fun formatBytes_scales() {
        assertEquals("512 B", AppUpdateNotifier.formatBytes(512))
        assertEquals("1.5 KB", AppUpdateNotifier.formatBytes(1536))
        assertEquals("2.0 MB", AppUpdateNotifier.formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun formatDownloadProgress_labels() {
        assertEquals("Downloading…", AppUpdateNotifier.formatDownloadProgress(0, null))
        assertEquals("Downloading 1.0 KB…", AppUpdateNotifier.formatDownloadProgress(1024, null))
        assertEquals("Downloading 42%…", AppUpdateNotifier.formatDownloadProgress(42, 100))
    }
}
