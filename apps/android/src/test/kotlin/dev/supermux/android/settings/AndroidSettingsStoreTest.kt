package dev.supermux.android.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidSettingsStoreTest {
    @Test fun roundTrip() = runTest {
        val file = File.createTempFile("settings", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = AndroidSettingsStore(ds)
        assertNull(store.string("k").first())
        store.putString("k", "v")
        assertEquals("v", store.string("k").first())
        store.putString("k", null)
        assertNull(store.string("k").first())
        scope.cancel()
        file.delete()
    }
}
