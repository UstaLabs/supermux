import {
  generateKeyPairSync, createHash, sign as edSign, verify as edVerify,
  createPublicKey, createPrivateKey, type KeyObject,
} from "crypto"
import { readFileSync, writeFileSync, existsSync, mkdirSync, chmodSync } from "fs"
import { dirname } from "path"

const B32 = "abcdefghijklmnopqrstuvwxyz234567" // RFC 4648 lower, no padding

function base32(buf: Buffer): string {
  let bits = 0, value = 0, out = ""
  for (const byte of buf) {
    value = (value << 8) | byte; bits += 8
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5 }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31]
  return out
}

/** Raw 32-byte Ed25519 public key → 26-char base32 hostId (128-bit hash prefix). */
export function hostIdFromPublicKey(publicKeyRaw: Buffer): string {
  const digest = createHash("sha256").update(publicKeyRaw).digest()
  return base32(digest.subarray(0, 16))
}

export interface HostIdentity {
  hostId: string
  publicKeyRaw: Buffer
  sign(message: Buffer): Buffer
  verify(message: Buffer, signature: Buffer): boolean
}

function rawPublicKey(pub: KeyObject): Buffer {
  // DER SPKI for Ed25519 is a fixed 44-byte prefix + 32-byte key.
  const der = pub.export({ type: "spki", format: "der" })
  return Buffer.from(der.subarray(der.length - 32))
}

function toIdentity(priv: KeyObject, pub: KeyObject): HostIdentity {
  const publicKeyRaw = rawPublicKey(pub)
  return {
    hostId: hostIdFromPublicKey(publicKeyRaw),
    publicKeyRaw,
    sign: (message) => edSign(null, message, priv),
    verify: (message, signature) => edVerify(null, message, pub, signature),
  }
}

export function loadOrCreateHostKey(path: string): HostIdentity {
  if (existsSync(path)) {
    const pem = readFileSync(path, "utf8")
    const priv = createPrivateKey(pem)
    return toIdentity(priv, createPublicKey(priv))
  }
  const { privateKey, publicKey } = generateKeyPairSync("ed25519")
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  writeFileSync(path, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 })
  chmodSync(path, 0o600) // umask-proof
  return toIdentity(privateKey, publicKey)
}
