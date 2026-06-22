import { expect, test } from "bun:test"
import { buildServiceAccountJwt, createFcmAdapter, createServiceAccountTokenGetter } from "./fcm"

const mkFetch = (r: Response) => (async () => r) as unknown as typeof fetch
const deps = { projectId: "p", getAccessToken: async () => "ya29", fetchImpl: mkFetch(new Response("", { status: 200 })) }

test("maps 200 → ok", async () => {
  const a = createFcmAdapter({ ...deps })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: true })
})

test("maps 404 (UNREGISTERED) → gone", async () => {
  const a = createFcmAdapter({ ...deps, fetchImpl: mkFetch(new Response('{"error":{"status":"UNREGISTERED"}}', { status: 404 })) })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true })
})

test("maps 400 (INVALID_ARGUMENT) → gone", async () => {
  const a = createFcmAdapter({ ...deps, fetchImpl: mkFetch(new Response('{"error":{"status":"INVALID_ARGUMENT"}}', { status: 400 })) })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true })
})

test("maps 503 (transient) → not gone", async () => {
  const a = createFcmAdapter({ ...deps, fetchImpl: mkFetch(new Response("upstream unavailable", { status: 503 })) })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: false })
})

// --- service-account OAuth2 path (fully offline) ---

// Generate a throwaway RSA keypair and export the private half as PKCS#8 PEM,
// matching the shape of a real Google service-account `private_key`.
async function makeFakeServiceAccount() {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey))
  const b64 = Buffer.from(pkcs8).toString("base64").replace(/(.{64})/g, "$1\n")
  const pem = `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`
  return { sa: { client_email: "svc@p.iam.gserviceaccount.com", private_key: pem }, publicKey: pair.publicKey }
}

test("buildServiceAccountJwt: well-formed RS256 assertion with correct claims + verifiable signature", async () => {
  const { sa, publicKey } = await makeFakeServiceAccount()
  const key = await crypto.subtle.importKey("pkcs8", new Uint8Array(Buffer.from(
    sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64")),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"])

  const jwt = await buildServiceAccountJwt(sa, key, 1_700_000_000)
  const [h, c, s] = jwt.split(".") as [string, string, string]
  expect([h, c, s].every(Boolean)).toBe(true)

  const header = JSON.parse(Buffer.from(h, "base64url").toString())
  const claims = JSON.parse(Buffer.from(c, "base64url").toString())
  expect(header).toEqual({ alg: "RS256", typ: "JWT" })
  expect(claims.iss).toBe("svc@p.iam.gserviceaccount.com")
  expect(claims.scope).toBe("https://www.googleapis.com/auth/firebase.messaging")
  expect(claims.aud).toBe("https://oauth2.googleapis.com/token")
  expect(claims.iat).toBe(1_700_000_000)
  expect(claims.exp).toBe(1_700_003_600)

  // Signature must verify over `header.claims` with the matching public key.
  const ok = await crypto.subtle.verify(
    { name: "RSASSA-PKCS1-v1_5" },
    publicKey,
    new Uint8Array(Buffer.from(s, "base64url")),
    new TextEncoder().encode(`${h}.${c}`),
  )
  expect(ok).toBe(true)
})

test("createServiceAccountTokenGetter: exchanges JWT for token, caches across calls", async () => {
  const { sa } = await makeFakeServiceAccount()
  let calls = 0
  let seenBody = ""
  const fetchImpl = (async (_url: string, init?: RequestInit) => {
    calls++
    seenBody = String(init?.body ?? "")
    return new Response(JSON.stringify({ access_token: "ya29.fresh", token_type: "Bearer", expires_in: 3600 }), { status: 200 })
  }) as unknown as typeof fetch

  const get = createServiceAccountTokenGetter(sa, fetchImpl)
  expect(await get()).toBe("ya29.fresh")
  expect(await get()).toBe("ya29.fresh") // served from cache
  expect(calls).toBe(1)

  const params = new URLSearchParams(seenBody)
  expect(params.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer")
  const assertion = params.get("assertion") ?? ""
  expect(assertion.split(".").length).toBe(3)
})
