import { expect, test } from "bun:test"
import { sealForDevice } from "./encrypt"

// openForTest mirrors what the native clients will do; it both tests and documents the format.
async function openForTest(sealed: string, privateKey: CryptoKey): Promise<string> {
  const [ephB64, saltB64, ivB64, ctB64] = sealed.split(".") as [string, string, string, string]
  const b = (s: string) => Uint8Array.from(Buffer.from(s, "base64url"))
  const ephPub = await crypto.subtle.importKey("raw", b(ephB64), { name: "ECDH", namedCurve: "P-256" }, false, [])
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: ephPub }, privateKey, 256)
  const hk = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"])
  const aesKey = await crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: b(saltB64), info: new TextEncoder().encode("supermux-push") },
    hk, { name: "AES-GCM", length: 256 }, false, ["decrypt"])
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b(ivB64) }, aesKey, b(ctB64))
  return new TextDecoder().decode(plain)
}

test("sealForDevice produces a blob a holder of the private key can open", async () => {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  const rawPub = Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
  const sealed = await sealForDevice(rawPub, JSON.stringify({ session: "s", text: "hi" }))
  const plain = await openForTest(sealed, kp.privateKey)
  expect(JSON.parse(plain)).toMatchObject({ session: "s", text: "hi" })
})

test("different seals of the same plaintext differ (fresh ephemeral key + iv)", async () => {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  const rawPub = Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
  const a = await sealForDevice(rawPub, "msg"), b = await sealForDevice(rawPub, "msg")
  expect(a).not.toEqual(b)
})
