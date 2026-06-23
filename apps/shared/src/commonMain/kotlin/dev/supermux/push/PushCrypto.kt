package dev.supermux.push

// Native-side counterpart to the broker's TS `sealForDevice` (src/core/push/encrypt.ts).
//
// Opens a sealed push blob: P-256 ECDH -> HKDF-SHA256 -> AES-256-GCM. Only the holder
// of the device's static P-256 private key can recover the plaintext, so the relay and
// Apple/Google only ever see ciphertext.
//
// WIRE FORMAT (must match the broker byte-for-byte):
//   blob = base64url(ephPubRaw) "." base64url(salt) "." base64url(iv) "." base64url(ct)
//   - ephPubRaw : ephemeral P-256 public key, raw uncompressed SEC1 point, 65 bytes
//                 (0x04 || X(32) || Y(32)).
//   - salt      : 16 bytes, HKDF-SHA256 salt.
//   - iv        : 12 bytes, AES-GCM nonce.
//   - ct        : AES-256-GCM ciphertext WITH the 16-byte tag appended.
//   Each part is UNPADDED base64url; the private key is standard (padded) base64.
//   HKDF info is the fixed ASCII string "supermux-push". No AAD.
//
// Platform actuals:
//   - jvm/android : javax.crypto + java.security (same JCA; jvmTest is the source of truth).
//   - ios         : CryptoKit (implemented in the iOS client task on the Mac).
//
// @param blob the 4-part dot-joined sealed blob from the broker.
// @param privateKeyPkcs8B64 the device P-256 private key, PKCS#8 DER, standard base64.
// @return the decrypted UTF-8 plaintext (typically JSON of the push payload).
expect fun openSealedPush(blob: String, privateKeyPkcs8B64: String): String
