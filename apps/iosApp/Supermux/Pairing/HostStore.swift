import Foundation
import Shared

/// Process-wide holder for the multi-host `PairedHostStore` (spec §3.2) plus the one-time migration
/// from the legacy single-host `(baseURL, token)`. The Apple mirror of Android's `HostStores`.
///
/// The store is built once over `KeychainHostPersistence` (UserDefaults metadata + one Keychain
/// entry per record); recordIds are random UUIDs. All list logic + migration live in the SHARED
/// `PairedHostStore` (KMP commonMain) — there is no per-platform reimplementation, only storage.
///
/// For now the app still drives its single live connection from `BrokerConfig` (unchanged — zero
/// regression); this layer makes the multi-host storage EXIST and be correctly seeded at launch so
/// the later fleet-list UI can render from it. Existing paired users land as `PairedHost[0]` with
/// ZERO re-pairing.
enum HostStore {
    /// The shared multi-host store (lazily built on first access; thread-safe `static let`).
    static let shared: PairedHostStore = PairedHostStore(
        persistence: KeychainHostPersistence(),
        newId: { UUID().uuidString }
    )

    /// One-time single-host → `PairedHost[0]` migration, run at launch. Idempotent and safe to call
    /// every launch: `PairedHostStore.migrateFromSingleHost` no-ops once the store holds any host.
    /// Reads the legacy storage the app has always used — `BrokerConfig.baseURL` (UserDefaults) +
    /// `KeychainStore` (Keychain) — and copies it into `PairedHost[0]`, so existing paired users
    /// migrate transparently with no re-pairing. Returns the effective first host (for logging).
    @discardableResult
    static func migrateFromLegacyIfNeeded() -> PairedHost? {
        let existing = shared.list()
        if !existing.isEmpty {
            // NSLog (not print) so the line reaches the unified log / Console — visible in
            // production diagnostics and reliably captured headlessly via `simctl … log`.
            NSLog("%@", "[supermux] host migration skipped: store already holds \(existing.count) host(s)")
            return existing.first
        }
        guard let baseURL = BrokerConfig.baseURL, !baseURL.isEmpty,
              let token = KeychainStore.load(), !token.isEmpty else {
            NSLog("%@", "[supermux] host migration: no legacy single-host to migrate (token/baseURL absent)")
            return nil
        }
        shared.migrateFromSingleHost(token: token, baseUrl: baseURL)
        let seeded = shared.list().first
        let url = seeded.map { $0.relayUrl ?? $0.directUrl ?? "?" } ?? "?"
        NSLog("%@", "[supermux] host migration: seeded legacy single-host as PairedHost[0] (url=\(url))")
        return seeded
    }

    /// Drop every paired host — called on unpair so the migrated `PairedHost[0]` doesn't linger as a
    /// stale record after the user forgets the host (which would otherwise make the launch migration
    /// skip forever and re-pairing to a different broker never reflect in the store). Keeps the
    /// single-record store in lockstep with the single-host truth during this transitional phase;
    /// the later add-host/forget UI will own richer per-host mutation.
    static func forgetAll() {
        for host in shared.list() {
            shared.remove(recordId: host.recordId)
        }
    }
}
