import Foundation
import Security
import Shared

/// iOS `HostPersistence` (spec §3.2), the Apple mirror of Android's `AndroidHostPersistence`.
/// The multi-host fleet is stored SPLIT:
///
///  - **Metadata** (recordId, hostId, displayName, direct/relay URLs, platform, version,
///    lastSeenAt — never the token) → one JSON string in **UserDefaults**, i.e. ordinary app
///    storage, the same place `BrokerConfig` keeps the single base URL.
///  - **Tokens** → the **Keychain** (`kSecClassGenericPassword`, the same access pattern as
///    `KeychainStore`), **one entry per recordId** under service `dev.supermux.hosts`. Deliberately
///    NOT one blob for the whole fleet: a single lost/undecryptable token must cost at most one host
///    (re-pair), never wipe the list.
///
/// The metadata⇄token split itself is the shared, framework-free `HostMetaCodec` (KMP commonMain),
/// reused verbatim here — the "no token in the metadata blob" guarantee is structurally enforced by
/// that codec, identically on Android and iOS. This class only moves bytes; the multi-host list
/// logic lives in the shared `PairedHostStore`.
final class KeychainHostPersistence: HostPersistence {
    /// Distinct Keychain service from the legacy single-token item (`KeychainStore`, service
    /// `dev.supermux.app` / account `device_token`) so enumeration returns only per-host tokens.
    private static let tokenService = "dev.supermux.hosts"
    private static let metaKey = "host_registry_meta"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: HostPersistence (called by the shared PairedHostStore)

    func loadAll() -> [PairedHost] {
        let metaJson = defaults.string(forKey: Self.metaKey)
        // Re-inject each record's token from the Keychain; a missing token yields "" (the codec
        // keeps the host so it stays visible + re-pairable rather than silently vanishing).
        return HostMetaCodec.shared.decode(metaJson: metaJson) { recordId in
            Self.tokenGet(recordId)
        }
    }

    func saveAll(hosts: [PairedHost]) {
        // Tokens first, metadata second (mirrors Android): metadata is the source of truth on load,
        // so a crash between the two writes leaves at worst an orphan token (pruned next save),
        // never a metadata host with no token. Prune tokens whose record is gone (best-effort local
        // revoke on forget), then write each live token.
        let liveIds = Set(hosts.map { $0.recordId })
        for orphan in Self.tokenAccounts().subtracting(liveIds) {
            Self.tokenRemove(orphan)
        }
        for host in hosts {
            Self.tokenPut(host.recordId, host.token)
        }
        defaults.set(HostMetaCodec.shared.encodeMeta(hosts: hosts), forKey: Self.metaKey)
    }

    // MARK: Per-record Keychain token store (mirrors KeychainStore's SecItem access)

    private static func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: tokenService,
            kSecAttrAccount as String: account,
        ]
    }

    private static func tokenPut(_ account: String, _ token: String) {
        #if os(macOS)
        var tokens = macTokens()
        tokens[account] = token
        writeMacTokens(tokens)
        #else
        SecItemDelete(baseQuery(account) as CFDictionary)
        var add = baseQuery(account)
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
        #endif
    }

    private static func tokenGet(_ account: String) -> String? {
        #if os(macOS)
        return macTokens()[account]
        #else
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        #if os(macOS)
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        #endif
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
        #endif
    }

    private static func tokenRemove(_ account: String) {
        #if os(macOS)
        var tokens = macTokens()
        tokens.removeValue(forKey: account)
        writeMacTokens(tokens)
        #else
        SecItemDelete(baseQuery(account) as CFDictionary)
        #endif
    }

    /// recordIds that currently have a stored token — used to prune orphans on save.
    private static func tokenAccounts() -> Set<String> {
        #if os(macOS)
        return Set(macTokens().keys)
        #else
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: tokenService,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true,
        ]
        query[kSecReturnData as String] = false
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return Set(items.compactMap { $0[kSecAttrAccount as String] as? String })
        #endif
    }

    #if os(macOS)
    private static var macTokensURL: URL {
        let environment = ProcessInfo.processInfo.environment
        let state = environment["MUX_STATE_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent(".mux/state", isDirectory: true)
        return state.appendingPathComponent("native-host-tokens.json")
    }

    private static func macTokens() -> [String: String] {
        guard let data = try? Data(contentsOf: macTokensURL),
              let decoded = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return decoded
    }

    private static func writeMacTokens(_ tokens: [String: String]) {
        let file = macTokensURL
        try? FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        guard let data = try? JSONEncoder().encode(tokens) else { return }
        try? data.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    #endif
}
