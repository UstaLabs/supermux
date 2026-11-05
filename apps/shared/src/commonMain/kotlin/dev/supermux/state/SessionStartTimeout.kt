package dev.supermux.state

/**
 * How long the app waits for `POST /sessions`, or null for the platform's own deadline. The broker
 * answers a session start once the worktree is made and the agent is up: up to ~26 s on a busy host
 * (card #75). Ktor CIO (desktop and Android) cuts every request at 15 s by default, so there the
 * launcher showed an error while the broker went on to create the session; they get 45 s. iOS
 * (NSURLSession, 60 s) and the web (no engine deadline, [dev.supermux.net.BrokerApi]'s 120 s) keep theirs.
 */
internal expect val SESSION_START_TIMEOUT_MS: Long?
