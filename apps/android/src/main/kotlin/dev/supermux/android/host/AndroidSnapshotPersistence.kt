package dev.supermux.android.host

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.supermux.host.HostSnapshot
import dev.supermux.host.HostSnapshotCodec
import dev.supermux.host.SnapshotPersistence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Android [SnapshotPersistence] (spec §5): the per-host offline-session cache as one schema-versioned
 * JSON string in an ordinary Jetpack **DataStore** (`host_snapshots`) — the SAME plain-app-storage
 * style [AndroidHostPersistence] uses for host metadata, and deliberately OUTSIDE the Keystore-backed
 * `supermux_secure` token store: the cache holds no secrets, only last-known session metadata for
 * rendering a disconnected host's greyed group. A read/write failure degrades to an empty cache
 * (the host re-fetches its live snapshot on connect); it is never fatal.
 *
 * The [dev.supermux.host.HostSnapshotStore] replace/prune logic sits on top and is platform-agnostic
 * — this class only moves bytes.
 */
class AndroidSnapshotPersistence(context: Context) : SnapshotPersistence {
    private val appContext = context.applicationContext
    private val key = stringPreferencesKey(KEY)

    override fun loadAll(): List<HostSnapshot> {
        val json = runCatching {
            runBlocking { appContext.snapshotDataStore.data.first()[key] }
        }.getOrNull()
        return HostSnapshotCodec.decode(json)
    }

    override fun saveAll(snapshots: List<HostSnapshot>) {
        val json = HostSnapshotCodec.encode(snapshots)
        runCatching {
            runBlocking { appContext.snapshotDataStore.edit { it[key] = json } }
        }.onFailure { Log.w(TAG, "snapshot cache save failed", it) }
    }

    private companion object {
        const val TAG = "SupermuxSnapshots"
        const val KEY = "snapshots"
    }
}

/** App-scoped DataStore holding the offline-snapshot cache JSON (no secrets — outside the secure store). */
private val Context.snapshotDataStore by preferencesDataStore(name = "host_snapshots")
