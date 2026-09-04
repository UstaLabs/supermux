package dev.supermux.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.supermux.state.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** DataStore-backed [SettingsStore]. Replaces the old `chat_drafts` / `launcher_state` stores
 *  (no migration: an unsent draft may be lost once on upgrade). */
class AndroidSettingsStore(private val ds: DataStore<Preferences>) : SettingsStore {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override fun string(key: String): Flow<String?> = ds.data.map { it[stringPreferencesKey(key)] }

    override suspend fun putString(key: String, value: String?) {
        ds.edit { p ->
            val k = stringPreferencesKey(key)
            if (value == null) p.remove(k) else p[k] = value
        }
    }
}
