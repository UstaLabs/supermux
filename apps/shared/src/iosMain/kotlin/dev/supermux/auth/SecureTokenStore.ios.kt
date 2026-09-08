package dev.supermux.auth

import platform.Foundation.NSUserDefaults

/**
 * iOS's [SecureTokenStore]: the LEGACY single-host pairing, exactly where the SwiftUI app put it.
 *
 * This is not the fleet store — the multi-host tokens live under `dev.supermux.hosts`, one item per
 * record ([dev.supermux.host.KeychainHostPersistence]). This actual reads and writes the ONE
 * pre-multi-host pairing: Keychain `dev.supermux.app` / `device_token` (Swift's `KeychainStore`)
 * plus `broker_base_url` in `standardUserDefaults` (Swift's `BrokerConfig`). Its only job is to be
 * migrated FROM, once, by `PairedHostStore.migrateFromSingleHost` — the same role the Android
 * actual plays in `HostStores.migrateFromLegacyIfNeeded`.
 *
 * `standardUserDefaults`, NOT the `group.dev.supermux.app` suite: the Swift app has always written
 * the base URL to the standard domain, and reading the suite would find nothing on a device that
 * has been paired for a year.
 */
actual class SecureTokenStore actual constructor() {

    actual fun save(token: String) =
        IosKeychain.put(IosKeychain.LEGACY_SERVICE, IosKeychain.LEGACY_ACCOUNT, token)

    actual fun load(): String? =
        IosKeychain.get(IosKeychain.LEGACY_SERVICE, IosKeychain.LEGACY_ACCOUNT)

    /** Drops both halves, matching Swift's `BrokerConfig.unpair()`. */
    actual fun clear() {
        IosKeychain.remove(IosKeychain.LEGACY_SERVICE, IosKeychain.LEGACY_ACCOUNT)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(BROKER_BASE_URL_KEY)
    }

    actual fun saveBaseUrl(url: String) {
        NSUserDefaults.standardUserDefaults.setObject(url, forKey = BROKER_BASE_URL_KEY)
    }

    actual fun loadBaseUrl(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(BROKER_BASE_URL_KEY)

    internal companion object {
        /** Swift `BrokerConfig.urlKey`. Verbatim — an upgrade must find the paired broker. */
        const val BROKER_BASE_URL_KEY = "broker_base_url"
    }
}
