import type { PlatformPushAdapter } from "../core/push/native-sender"
import { makeLogger } from "../shared/log"

const log = makeLogger("relay/fcm")

export interface FcmDeps {
  projectId: string
  getAccessToken: () => Promise<string>
  fetchImpl?: typeof fetch
}

export function createFcmAdapter(deps: FcmDeps): PlatformPushAdapter {
  const f = deps.fetchImpl ?? fetch
  return {
    // `payload` is the relay's already-encrypted blob (`{ ciphertext }`), not the
    // broker-side PushPayload — we only reuse PlatformPushAdapter's shape here.
    // Data-only message (no `notification` key) so the client controls display.
    // FCM data-only messages are inherently silent on Android; opts.silent is a no-op here.
    async send(token, payload: any, _opts?: { silent?: boolean }) {
      const at = await deps.getAccessToken()
      const res = await f(`https://fcm.googleapis.com/v1/projects/${deps.projectId}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${at}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token, data: { d: payload.ciphertext }, android: { priority: "high" } } }),
      })
      if (res.status === 200) return { ok: true }
      const body = await res.text()
      // UNREGISTERED (404) means the token is dead; INVALID_ARGUMENT (400) means a
      // malformed token (our payload is a constant data-only message, so a 400 here
      // is the token, not the body). Both → drop. Everything else is transient.
      const gone = res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT/.test(body)
      return { ok: false, gone }
    },
  }
}

// ---------------------------------------------------------------------------
// Service-account OAuth2 access-token getter (A5 wires this into createFcmAdapter).
//
// Turns a Firebase service-account JSON into a cached OAuth2 access token using
// the JWT-bearer grant: we mint and RS256-sign a short-lived assertion, exchange
// it at the token endpoint for an access token, and cache that token until ~60s
// before it expires.
// ---------------------------------------------------------------------------

export interface ServiceAccount {
  client_email: string
  private_key: string
  token_uri?: string
}

const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
const FIREBASE_MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
// Refresh a little before the real expiry so an in-flight send never races a
// token that has just lapsed.
const EXPIRY_SKEW_MS = 60 * 1000

function base64url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64url")
}

function base64urlJson(obj: unknown): string {
  return base64url(new TextEncoder().encode(JSON.stringify(obj)))
}

// Decode a PKCS#8 PEM (the `private_key` field of a Google service-account JSON,
// which carries literal `\n`-escaped newlines when read from env) to raw DER.
function pemToDer(pem: string): Uint8Array {
  const b64 = pem
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN [^-]+-----/, "")
    .replace(/-----END [^-]+-----/, "")
    .replace(/\s+/g, "")
  return new Uint8Array(Buffer.from(b64, "base64"))
}

/**
 * Build a signed JWT-bearer assertion for the service account. Exported so the
 * (offline) unit test can assert the assertion is well-formed without a network
 * round-trip.
 */
export async function buildServiceAccountJwt(
  sa: ServiceAccount,
  signingKey: CryptoKey,
  nowSec = Math.floor(Date.now() / 1000),
): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" }
  const claims = {
    iss: sa.client_email,
    scope: FIREBASE_MESSAGING_SCOPE,
    aud: sa.token_uri ?? DEFAULT_TOKEN_URI,
    iat: nowSec,
    exp: nowSec + 3600,
  }
  const signingInput = `${base64urlJson(header)}.${base64urlJson(claims)}`
  const sig = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    signingKey,
    new TextEncoder().encode(signingInput),
  )
  return `${signingInput}.${base64url(new Uint8Array(sig))}`
}

export function createServiceAccountTokenGetter(
  saJson: ServiceAccount,
  fetchImpl: typeof fetch = fetch,
): () => Promise<string> {
  let cachedToken: string | null = null
  let cachedUntil = 0
  let signingKey: Promise<CryptoKey> | null = null
  let inflight: Promise<string> | null = null

  function importKey(): Promise<CryptoKey> {
    // Import once; reuse the (non-extractable) key for every signing call. Only
    // cache on success so a transient failure doesn't poison the getter — the
    // next call retries the import.
    if (!signingKey) {
      signingKey = crypto.subtle
        .importKey(
          "pkcs8",
          pemToDer(saJson.private_key),
          { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
          false,
          ["sign"],
        )
        .catch((err) => {
          signingKey = null
          throw err
        })
    }
    return signingKey
  }

  async function refresh(): Promise<string> {
    const key = await importKey()
    const assertion = await buildServiceAccountJwt(saJson, key)
    const tokenUri = saJson.token_uri ?? DEFAULT_TOKEN_URI
    const res = await fetchImpl(tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    })
    if (!res.ok) {
      const body = await res.text().catch(() => "")
      throw new Error(`fcm token exchange failed: ${res.status} ${body}`)
    }
    const json = (await res.json()) as { access_token?: string; expires_in?: number }
    if (!json.access_token) throw new Error("fcm token exchange returned no access_token")
    const ttlMs = (json.expires_in ?? 3600) * 1000
    cachedToken = json.access_token
    cachedUntil = Date.now() + ttlMs - EXPIRY_SKEW_MS
    return cachedToken
  }

  return async function getAccessToken(): Promise<string> {
    if (cachedToken && Date.now() < cachedUntil) return cachedToken
    // Coalesce concurrent refreshes so a burst of sends triggers one exchange.
    if (!inflight) {
      inflight = refresh()
        .catch((err) => {
          log.error("fcm_token_refresh_failed", { err: err?.message ?? String(err) })
          throw err
        })
        .finally(() => {
          inflight = null
        })
    }
    return inflight
  }
}
