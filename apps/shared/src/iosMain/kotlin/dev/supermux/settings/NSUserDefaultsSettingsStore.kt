package dev.supermux.settings

import dev.supermux.state.SettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDefaultsDidChangeNotification

/**
 * iOS's [SettingsStore], over `NSUserDefaults`.
 *
 * `standardUserDefaults` and NOT the `group.dev.supermux.app` suite. The suite exists so the
 * notification-service extension can share PUSH state with the app, and that state stays Swift; the
 * app's own preferences have always been in the standard domain, and moving them would orphan every
 * value an upgrading user has set. The migration in [migrateSwiftPrefsOnce] reads the same domain
 * for exactly that reason.
 *
 * [string] genuinely OBSERVES the value rather than reading it once. That is not decoration: the
 * shared Appearance screen writes `appearance:mode` through this store and the root theme reads it
 * through this store, so a `flowOf(currentValue)` would mean changing the theme in Settings did
 * nothing until the next launch. `NSUserDefaultsDidChangeNotification` does not say WHICH key
 * changed, so every write wakes every collector — [distinctUntilChanged] is what keeps that from
 * turning into a recomposition storm, and it is why the flow re-reads instead of trusting the
 * notification's payload.
 */
class NSUserDefaultsSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SettingsStore {

    override fun string(key: String): Flow<String?> = callbackFlow {
        trySend(defaults.stringForKey(key))
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSUserDefaultsDidChangeNotification,
            `object` = defaults,
            queue = null,
        ) { _ ->
            trySend(defaults.stringForKey(key))
        }
        awaitClose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }.distinctUntilChanged()

    /**
     * Writes synchronously. `NSUserDefaults` is an in-memory dictionary flushed by the system, so
     * this is a dictionary assignment rather than disk I/O — there is nothing to move off the
     * caller's thread, and the change notification the collectors above need is posted on this one.
     */
    override suspend fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}
