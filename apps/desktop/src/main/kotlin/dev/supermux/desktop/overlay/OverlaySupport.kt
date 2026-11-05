// Where the assistant overlay (#62) can float.
package dev.supermux.desktop.overlay

/**
 * macOS only, for now. Windows gets it with its own pass (its hot key, keeping its windows out of
 * captures); until then its switch stays out of Settings. A Linux desktop's compositor may not keep it on top.
 */
internal fun overlaySupportedOn(osName: String): Boolean = osName.startsWith("Mac")
