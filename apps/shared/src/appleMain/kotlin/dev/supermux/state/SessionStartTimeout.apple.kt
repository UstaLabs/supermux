package dev.supermux.state

/** iOS + watchOS: NSURLSession's own 60 s already covers a session start. */
internal actual val SESSION_START_TIMEOUT_MS: Long? = null
