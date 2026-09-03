package dev.supermux.desktop.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSettingsStoreTest {
    @Test fun roundTripsPersistsAndClears() = runTest {
        val dir = Files.createTempDirectory("settings")
        val store = DesktopSettingsStore(dir.resolve("settings.json"))
        assertNull(store.string("k").first())
        store.putString("k", "v")
        assertEquals("v", store.string("k").first())
        assertEquals("v", DesktopSettingsStore(dir.resolve("settings.json")).string("k").first())
        store.putString("k", null)
        assertNull(store.string("k").first())
    }
}
