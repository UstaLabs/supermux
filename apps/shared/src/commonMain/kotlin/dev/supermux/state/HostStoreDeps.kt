package dev.supermux.state

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

/** Async key-value settings. One actual per platform (DataStore on Android, JSON file on desktop). */
interface SettingsStore {
    fun string(key: String): Flow<String?>
    suspend fun putString(key: String, value: String?)
}

/** Every key both apps persist, in one place so they store the same thing. */
object SettingsKeys {
    fun draft(sessionId: String) = "draft:$sessionId"
    const val LAUNCHER_DRAFT = "launcher:draft"
    const val LAUNCHER_PREFS = "launcher:prefs"
    const val HOST_FILTER = "host:filter"
    const val EDITOR_PREFS = "editor:prefs"
    const val VOICE_STT = "voice:stt"
    const val VOICE_CLEANUP = "voice:cleanup"
    const val VOICE_TTS = "voice:tts"
}

/** The whole platform surface a HostStore / FleetStore needs. */
class HostStoreDeps(
    /** `timeoutMs == null` → engine default; `120_000` for the long dictation POST. WebSockets plugin always installed. */
    val httpFactory: (timeoutMs: Long?) -> HttpClient,
    val settings: SettingsStore,
    val clock: Clock = Clock.System,
) {
    fun nowIso(): String = clock.now().toString()
    fun nowMs(): Long = clock.now().toEpochMilliseconds()
}
