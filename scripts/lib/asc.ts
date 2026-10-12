// scripts/lib/asc.ts
// Minimal App Store Connect REST client shared by the TestFlight scripts.
//
// Required env (the same key trio the release workflow feeds the upload):
//   ASC_API_KEY_P8_BASE64   base64 of the AuthKey_<id>.p8
//   ASC_API_KEY_ID          the key id (kid)
//   ASC_API_ISSUER_ID       the issuer id
//
// Deliberately dependency-free: these scripts run on a bare `oven-sh/setup-bun`
// runner with no `bun install` step, so anything imported here must be built in.

export type ApiResult = { status: number; body: any }

export function requireEnv(name: string): string {
  const v = process.env[name]
  if (!v) {
    console.error(`missing required env ${name}`)
    process.exit(1)
  }
  return v
}

export function fail(message: string, result?: ApiResult): never {
  console.error(message)
  if (result) console.error(`HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 600)}`)
  process.exit(1)
}

function base64url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

// ES256 JWT signed with the ASC key. WebCrypto's ECDSA output is already the raw r||s pair
// JOSE wants, so no DER unwrapping is needed.
async function mintToken(keyId: string, issuerId: string, p8Base64: string): Promise<string> {
  const pem = Buffer.from(p8Base64, "base64").toString("utf8")
  const der = Buffer.from(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64")
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  )
  const now = Math.floor(Date.now() / 1000)
  const header = base64url(Buffer.from(JSON.stringify({ alg: "ES256", kid: keyId, typ: "JWT" })))
  const payload = base64url(
    Buffer.from(JSON.stringify({ iss: issuerId, iat: now, exp: now + 900, aud: "appstoreconnect-v1" })),
  )
  const signingInput = `${header}.${payload}`
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, Buffer.from(signingInput))
  return `${signingInput}.${base64url(new Uint8Array(sig))}`
}

export type Asc = {
  api: (method: string, path: string, body?: unknown) => Promise<ApiResult>
  appId: (bundleId: string) => Promise<string>
}

export function ascFromEnv(): Asc {
  const keyId = requireEnv("ASC_API_KEY_ID")
  const issuerId = requireEnv("ASC_API_ISSUER_ID")
  const p8Base64 = requireEnv("ASC_API_KEY_P8_BASE64")

  // The token is minted per call: polling can outlive a single token's 15-minute life.
  const api = async (method: string, path: string, body?: unknown): Promise<ApiResult> => {
    const res = await fetch(`https://api.appstoreconnect.apple.com${path}`, {
      method,
      headers: {
        authorization: `Bearer ${await mintToken(keyId, issuerId, p8Base64)}`,
        "content-type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await res.text()
    return { status: res.status, body: text ? JSON.parse(text) : {} }
  }

  return {
    api,
    appId: async (bundleId: string) => {
      const res = await api("GET", `/v1/apps?filter%5BbundleId%5D=${encodeURIComponent(bundleId)}&limit=1`)
      if (res.status !== 200) fail(`could not look up app ${bundleId}`, res)
      const id: string | undefined = res.body.data?.[0]?.id
      if (!id) fail(`no App Store Connect app for bundle id ${bundleId}`)
      return id
    },
  }
}
