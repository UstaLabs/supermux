// Payload encryption for native push (broker -> device), ECIES-style sealed box.
//
// Scheme: P-256 ECDH -> HKDF-SHA256 -> AES-256-GCM, all via WebCrypto.
// The broker seals a notification preview (JSON of PushPayload) to a device's
// public key; only that device (holding the P-256 private key) can open it, so
// the relay and Apple/Google only ever see ciphertext. P-256 is chosen so the
// iOS client can keep its private key in the Secure Enclave.
//
// ── WIRE FORMAT (the iOS/Android clients must match this byte-for-byte) ──────
//
//   sealed = base64url(ephPubRaw) "." base64url(salt) "." base64url(iv) "." base64url(ct)
//
// A 4-part, dot-joined ASCII string. Each part is unpadded base64url
// (RFC 4648 §5, "-"/"_" alphabet, no "=" padding). Split on "." → exactly 4 parts:
//
//   [0] ephPubRaw : ephemeral P-256 public key, raw/uncompressed SEC1 point,
//                   65 bytes (0x04 || X(32) || Y(32)). Fresh per message.
//   [1] salt      : 16 random bytes, the HKDF-SHA256 salt. Fresh per message.
//   [2] iv        : 12 random bytes, the AES-GCM nonce. Fresh per message.
//   [3] ct        : AES-256-GCM ciphertext WITH the 16-byte auth tag appended
//                   (WebCrypto/CryptoKit/Tink convention: tag trails the body).
//
// To OPEN (receiver, holds the static P-256 private key):
//   1. ephPub = importKey("raw", ephPubRaw, ECDH P-256)
//   2. shared = ECDH(ephPub, devicePrivate) → 256 bits (the X coordinate)
//   3. key    = HKDF-SHA256(ikm=shared, salt=salt, info="supermux-push") → AES-256 key
//   4. plain  = AES-256-GCM-decrypt(key, iv, ct)   // ct carries the tag
//
// HKDF info is the fixed ASCII string "supermux-push" (domain separation).
// There is no associated data (AAD) — integrity is over the ciphertext only.
// A reference receiver lives in encrypt.test.ts (openForTest).

const HKDF_INFO = new TextEncoder().encode("supermux-push")
const SALT_BYTES = 16
const IV_BYTES = 12

const b64url = (bytes: ArrayBuffer | Uint8Array): string => Buffer.from(bytes as any).toString("base64url")
const fromB64url = (s: string): Uint8Array => Uint8Array.from(Buffer.from(s, "base64url"))

/**
 * Seal `plaintext` to a device's P-256 public key.
 *
 * @param devicePubB64url uncompressed P-256 public point (65 bytes, 0x04 prefix),
 *   base64url-encoded — the value the device registered.
 * @param plaintext UTF-8 string to encrypt (typically `JSON.stringify(PushPayload)`).
 * @returns the 4-part dot-joined sealed blob (see WIRE FORMAT above).
 */
export async function sealForDevice(devicePubB64url: string, plaintext: string): Promise<string> {
  // 1. Import the device (recipient) public key.
  const devicePub = await crypto.subtle.importKey(
    "raw",
    fromB64url(devicePubB64url),
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  )

  // 2. Fresh ephemeral P-256 keypair (one per message — forward secrecy + no nonce reuse).
  const eph = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])

  // 3. ECDH: shared secret = 256-bit X coordinate of eph_priv * device_pub.
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: devicePub }, eph.privateKey, 256)

  // 4. HKDF-SHA256(shared, salt, "supermux-push") → AES-256-GCM key.
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES))
  const hk = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"])
  const aesKey = await crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt, info: HKDF_INFO },
    hk,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  )

  // 5. AES-256-GCM encrypt with a fresh 12-byte IV (ciphertext includes the 16-byte tag).
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, aesKey, new TextEncoder().encode(plaintext))

  // 6. Export the ephemeral public key raw (65-byte uncompressed point).
  const ephPubRaw = await crypto.subtle.exportKey("raw", eph.publicKey)

  // 7. Emit the 4-part wire format.
  return [b64url(ephPubRaw), b64url(salt), b64url(iv), b64url(ct)].join(".")
}
