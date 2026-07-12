package dev.supermux.android.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose destinations (replace the old `when(route)` strings).
 * `Home` is the keep-alive list↔chat destination; the active session id is hoisted
 * state in MainActivity, not a nav argument.
 */
@Serializable object Home
@Serializable object NewSession
@Serializable object AddHost
@Serializable object Settings
@Serializable object Usage
@Serializable object Devices
@Serializable object Archived
@Serializable object Proxies
@Serializable object Displays
@Serializable object Appearance
