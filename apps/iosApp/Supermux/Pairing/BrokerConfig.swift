import Foundation

/// Persisted broker connection: base URL in UserDefaults, token in the Keychain.
enum BrokerConfig {
    private static let urlKey = "broker_base_url"

    static var baseURL: String? {
        get { UserDefaults.standard.string(forKey: urlKey) }
        set { UserDefaults.standard.set(newValue, forKey: urlKey) }
    }
    static var token: String? { KeychainStore.load() }
    static var isPaired: Bool { baseURL != nil && token != nil }

    static func pair(_ p: PairToken) {
        baseURL = p.baseURL
        KeychainStore.save(p.token)
    }

    static func unpair() {
        UserDefaults.standard.removeObject(forKey: urlKey)
        KeychainStore.clear()
    }
}
