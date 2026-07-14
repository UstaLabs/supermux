//
//  PushKeypair.swift
//  Supermux
//
//  The device's static P-256 keypair for native push (APNs).
//
//  - The PRIVATE key never leaves the device: it is stored in the Keychain as the
//    32-byte `rawRepresentation` of a `P256.KeyAgreement.PrivateKey`, under a shared
//    Keychain ACCESS GROUP (`$(AppIdentifierPrefix)dev.supermux.app.push`) so the
//    Notification Service Extension can load the SAME key and decrypt sealed pushes.
//  - The PUBLIC key is exported as a raw uncompressed SEC1 point
//    (`x963Representation` = `0x04 || X(32) || Y(32)`, 65 bytes), base64url-no-pad —
//    exactly what the broker's `/push/device` endpoint expects as `pubkey` and what
//    its `sealForDevice` re-imports to seal payloads (see `src/core/push/encrypt.ts`).
//
//  Generated once on first `loadOrCreate()` and reused thereafter, so the broker's
//  stored pubkey stays valid across app launches AND across the app↔NSE boundary
//  (they share the key via the Keychain access group, not via process state).
//
//  P-256 / `P256.KeyAgreement` is the CryptoKit analog of the broker's WebCrypto
//  `ECDH P-256` and the Android `secp256r1` keypair — the three must interoperate.
//

import CryptoKit
import Foundation
import Security

/// base64url-no-pad encode (RFC 4648 §5: `+`→`-`, `/`→`_`, strip `=`).
private func base64urlNoPad(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

/// The device's persistent push keypair. Shared by the app and the NSE through the
/// Keychain access group, so use the singleton (`PushKeypair.shared`) in both targets.
final class PushKeypair {
    static let shared = PushKeypair()

    // Keychain coordinates. The ACCESS GROUP is what makes the same item visible to
    // both the app and the NSE (both carry this group in `keychain-access-groups`).
    // It is the team app-id prefix (`57L7J9XA89.` for team 57L7J9XA89) + the group id.
    // Hard-coded (not `$(AppIdentifierPrefix)…`) so it resolves identically at runtime
    // and in the entitlements file — on the simulator there is no profile to expand
    // `$(AppIdentifierPrefix)`, so the literal prefix is what gets embedded + matched.
    static let accessGroup = "57L7J9XA89.dev.supermux.app.push"
    private static let service = "dev.supermux.app.push"
    private static let account = "push_keyagreement_p256_raw"

    private let lock = NSLock()
    private var cached: P256.KeyAgreement.PrivateKey?

    private init() {}

    // MARK: - Public API

    /// Load the persisted private key, generating + storing one on first use.
    /// Safe to call from multiple threads / both targets (Keychain is the source of truth).
    @discardableResult
    func loadOrCreate() -> P256.KeyAgreement.PrivateKey {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        if let raw = Self.keychainLoad(),
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            cached = key
            return key
        }
        let key = P256.KeyAgreement.PrivateKey()
        Self.keychainSave(key.rawRepresentation)
        cached = key
        // Make the pubkey discoverable on the simulator for the `simctl push` test
        // harness (no-op cost in production; container files aren't shipped).
        Self.dumpPublicKeyForTesting(key)
        return key
    }

    /// The static private key (loads/creates on first access).
    var privateKey: P256.KeyAgreement.PrivateKey { loadOrCreate() }

    /// Raw uncompressed public point (`0x04||X||Y`, 65 bytes), base64url-no-pad — the
    /// value registered with the broker's `/push/device` as `pubkey`.
    var publicKeyB64Url: String {
        base64urlNoPad(privateKey.publicKey.x963Representation)
    }

    // MARK: - Keychain (shared access group)

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    private static func keychainSave(_ raw: Data) {
        #if os(macOS)
        writeMacKey(raw)
        #else
        SecItemDelete(baseQuery() as CFDictionary)
        var add = baseQuery()
        add[kSecValueData as String] = raw
        // AfterFirstUnlock: the NSE may run (decrypt a push) while the device is locked
        // but after the first post-boot unlock — the key must be readable then.
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
        #endif
    }

    private static func keychainLoad() -> Data? {
        #if os(macOS)
        return try? Data(contentsOf: macKeyURL)
        #else
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        #if os(macOS)
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        #endif
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return data
        #endif
    }

    #if os(macOS)
    private static var macKeyURL: URL {
        let environment = ProcessInfo.processInfo.environment
        let state = environment["MUX_STATE_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent(".mux/state", isDirectory: true)
        return state.appendingPathComponent("native-push-key")
    }

    private static func writeMacKey(_ raw: Data) {
        let file = macKeyURL
        try? FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try? raw.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    #endif

    // MARK: - Test harness helper

    /// Log the public key and, on the simulator, write it to `Documents/push_pubkey.txt`
    /// so the `simctl push` harness can read it out of the app container. Everywhere else
    /// it is only logged: on a real iOS device the container file is never extracted, and
    /// on macOS `.documentDirectory` in an UNSANDBOXED build (dev/test hosts) is the user's
    /// real `~/Documents` — a TCC-protected folder where this write blocks on a consent
    /// prompt (which hangs launch forever in a headless run, e.g. the unit-test host).
    private static func dumpPublicKeyForTesting(_ key: P256.KeyAgreement.PrivateKey) {
        let pub = base64urlNoPad(key.publicKey.x963Representation)
        NSLog("[supermux push] public key (b64url): %@", pub)
        #if targetEnvironment(simulator)
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? pub.write(to: dir.appendingPathComponent("push_pubkey.txt"),
                           atomically: true, encoding: .utf8)
        }
        #endif
    }
}
