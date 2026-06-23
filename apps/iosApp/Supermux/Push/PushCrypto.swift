//
//  PushCrypto.swift
//  Supermux
//
//  Decrypts supermux push payloads sealed by the broker's `sealForDevice`.
//
//  Wire format (must match the broker exactly):
//    P-256 ECDH -> HKDF-SHA256 -> AES-256-GCM.
//    Blob = 4 dot-joined base64url parts: `ephPub.salt.iv.ct`
//      - ephPub: raw uncompressed P-256 point, 65 bytes (0x04 || X || Y)
//      - salt:   16 bytes (HKDF salt)
//      - iv:     12 bytes (AES-GCM nonce)
//      - ct:     AES-GCM ciphertext with the 16-byte tag appended
//    HKDF info = ASCII "supermux-push". No AAD.
//
//  VERIFIED against a real broker `sealForDevice` output via a standalone
//  `swift` run on macOS (Swift 6.3.2 / CryptoKit). The key import that works is
//  `P256.KeyAgreement.PrivateKey(derRepresentation:)` fed the PKCS#8 DER bytes
//  directly (no SEC1/raw-scalar fallback needed). This is the iOS analog of the
//  already-proven Kotlin/JVM decrypt; CryptoKit is Swift-only so the iOS path
//  lives here.
//
//  Used by the Notification Service Extension (NSE) to open push payloads.
//

import CryptoKit
import Foundation

enum PushCryptoError: Error { case badFormat }

func b64url(_ s: Substring) -> Data {
    var t = String(s).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while t.count % 4 != 0 { t += "=" }
    return Data(base64Encoded: t)!
}

/// Opens a supermux push blob sealed by the broker (P-256 ECDH -> HKDF-SHA256 -> AES-256-GCM).
func openSealedPush(_ blob: String, privatePkcs8B64: String) throws -> String {
    let p = blob.split(separator: ".")
    guard p.count == 4 else { throw PushCryptoError.badFormat }
    let eph = b64url(p[0]), salt = b64url(p[1]), iv = b64url(p[2]), ct = b64url(p[3])
    let priv = try P256.KeyAgreement.PrivateKey(derRepresentation: Data(base64Encoded: privatePkcs8B64)!)  // PKCS#8 DER
    let ephPub = try P256.KeyAgreement.PublicKey(x963Representation: eph)                                  // 0x04||X||Y
    let shared = try priv.sharedSecretFromKeyAgreement(with: ephPub)
    let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                             sharedInfo: Data("supermux-push".utf8), outputByteCount: 32)
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv),
                                    ciphertext: ct.prefix(ct.count - 16), tag: ct.suffix(16))
    return String(data: try AES.GCM.open(box, using: key), encoding: .utf8)!
}
