import Foundation
import Security

/// Watch-local credential store: broker base URL in UserDefaults, device token in
/// the watch Keychain. The watch is a separate device from the phone, so this is the
/// watch's OWN copy of the credentials (provisioned once via WatchConnectivity, then
/// the watch connects to the broker independently). Mirrors the iOS `KeychainStore`
/// (service `dev.supermux.app`, account `device_token`).
enum WatchKeychain {
    private static let service = "dev.supermux.app"
    private static let account = "device_token"
    private static let baseURLKey = "broker_base_url"

    static func save(baseURL: String, token: String) {
        UserDefaults.standard.set(baseURL, forKey: baseURLKey)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load() -> (baseURL: String, token: String)? {
        guard let baseURL = UserDefaults.standard.string(forKey: baseURLKey), !baseURL.isEmpty else { return nil }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let token = String(data: data, encoding: .utf8), !token.isEmpty
        else { return nil }
        return (baseURL, token)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: baseURLKey)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
