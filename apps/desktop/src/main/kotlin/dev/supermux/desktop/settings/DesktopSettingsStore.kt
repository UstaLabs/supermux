package dev.supermux.desktop.settings

import dev.supermux.state.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** JSON-file-backed [SettingsStore]. Whole map rewritten on every put; reads are in-memory. */
class DesktopSettingsStore(private val file: Path) : SettingsStore {
    private val ser = MapSerializer(String.serializer(), String.serializer())
    private val state = MutableStateFlow(load())

    private fun load(): Map<String, String> =
        if (Files.exists(file)) runCatching { Json.decodeFromString(ser, Files.readString(file)) }.getOrDefault(emptyMap())
        else emptyMap()

    override fun string(key: String): Flow<String?> = state.map { it[key] }

    override suspend fun putString(key: String, value: String?) {
        val next = if (value == null) state.value - key else state.value + (key to value)
        state.value = next
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, Json.encodeToString(ser, next))
    }
}
