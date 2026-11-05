package dev.supermux.state

/** JVM + Android (Ktor CIO, 15 s per request by default): a session start may take 45 s. */
internal actual val SESSION_START_TIMEOUT_MS: Long? = 45_000
