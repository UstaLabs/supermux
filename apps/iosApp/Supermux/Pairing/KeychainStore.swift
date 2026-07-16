import Foundation
import Security

/// Minimal Keychain wrapper for the device bearer token. The Swift app owns the
/// token and passes it into the shared `BrokerClient`/`BrokerApi` (which take a
/// plain `token: String`), so the KMP `SecureTokenStore` actual is unused on iOS.
enum KeychainStore {
    private static let service = "dev.supermux.app"
    private static let account = "device_token"

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func save(_ token: String) {
        #if os(macOS)
        writeMacToken(token)
        #else
        SecItemDelete(baseQuery() as CFDictionary)
        var add = baseQuery()
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
        #endif
    }

    static func load() -> String? {
        #if os(macOS)
        return try? String(contentsOf: macTokenURL, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        #else
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        #if os(macOS)
        // Reinstalling a local build can change its designated requirement. Fail closed instead
        // of letting Keychain authorization block the main thread before the first window appears.
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        #endif
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
        #endif
    }

    static func clear() {
        #if os(macOS)
        try? FileManager.default.removeItem(at: macTokenURL)
        #else
        SecItemDelete(baseQuery() as CFDictionary)
        #endif
    }

    #if os(macOS)
    private static var macTokenURL: URL {
        let environment = ProcessInfo.processInfo.environment
        let state = environment["MUX_STATE_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent(".mux/state", isDirectory: true)
        return state.appendingPathComponent("native-client-token")
    }

    private static func writeMacToken(_ token: String) {
        let file = macTokenURL
        try? FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try? Data(token.utf8).write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    #endif
}
