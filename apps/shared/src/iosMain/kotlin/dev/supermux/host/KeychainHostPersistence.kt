package dev.supermux.host

import dev.supermux.auth.IosKeychain
import platform.Foundation.NSUserDefaults

/**
 * iOS [HostPersistence] — the Kotlin twin of the SwiftUI app's `KeychainHostPersistence.swift`,
 * reading and writing the SAME storage.
 *
 * That is the requirement, not a nicety: cluster H2 replaces the Swift shell with the Compose one
 * on a device that is already paired, without an uninstall. If this class disagreed with the Swift
 * one about a service name, a key, or the order of two writes, every existing user would open the
 * new build to an empty fleet.
 *
 * The split (spec §3.2) is identical on both sides:
 *  - **metadata** — one JSON string under `host_registry_meta` in `standardUserDefaults`, produced
 *    by the shared [HostMetaCodec], which structurally cannot contain a token;
 *  - **tokens** — one Keychain generic-password item per `recordId` under the service
 *    `dev.supermux.hosts`, so a single unreadable token costs one host (re-pair), never the list.
 *
 * `standardUserDefaults` and not the app-group suite, deliberately: the app group holds push state,
 * which the NSE also reads and which stays Swift. The host registry has always lived in the
 * standard domain, and moving it would strand every paired device.
 */
class KeychainHostPersistence(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : HostPersistence {

    override fun loadAll(): List<PairedHost> =
        HostMetaCodec.decode(defaults.stringForKey(META_KEY)) { recordId ->
            IosKeychain.get(IosKeychain.HOSTS_SERVICE, recordId)
        }

    /**
     * Prune orphan tokens → write tokens → write metadata.
     *
     * The order is the contract, and it is the same on Android and in Swift: metadata is the truth
     * at load time, so a crash between the writes leaves at worst a token with no host (pruned on
     * the next save) and never a host with no token — which would look to the user like a working
     * entry that cannot connect. Pruning first is the best-effort local revoke when a host is
     * forgotten.
     */
    override fun saveAll(hosts: List<PairedHost>) {
        val live = hosts.map { it.recordId }.toSet()
        for (orphan in IosKeychain.accounts(IosKeychain.HOSTS_SERVICE) - live) {
            IosKeychain.remove(IosKeychain.HOSTS_SERVICE, orphan)
        }
        for (host in hosts) {
            IosKeychain.put(IosKeychain.HOSTS_SERVICE, host.recordId, host.token)
        }
        defaults.setObject(HostMetaCodec.encodeMeta(hosts), forKey = META_KEY)
    }

    internal companion object {
        /** Swift `KeychainHostPersistence.metaKey`. Verbatim. */
        const val META_KEY = "host_registry_meta"
    }
}
