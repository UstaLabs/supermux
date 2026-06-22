import http2 from "node:http2"
import type { PlatformPushAdapter } from "../core/push/native-sender"
import { makeLogger } from "../shared/log"

const log = makeLogger("relay/apns")

export interface ApnsConfig {
  keyP8: string
  keyId: string
  teamId: string
  bundleId: string
  sandbox: boolean
}

export type H2Post = (o: {
  host: string
  path: string
  headers: Record<string, string>
  body: string
}) => Promise<{ status: number; body: string }>

// APNs rejects a provider token whose `iat` is more than 1 hour old, and asks
// providers to refresh no more than once per 20 min / no less than once per
// 60 min. 50 minutes sits comfortably inside that window.
const JWT_TTL_MS = 50 * 60 * 1000

function base64url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64url")
}

function base64urlJson(obj: unknown): string {
  return base64url(new TextEncoder().encode(JSON.stringify(obj)))
}

// Decode a PKCS#8 PEM (the contents of an Apple `.p8` auth key) to raw DER bytes.
// Handles both real newlines and literal `\n`-escaped newlines (as stored in
// Coolify / Docker secrets where multi-line values are pasted with `\n`).
function pemToDer(pem: string): Uint8Array {
  const b64 = pem
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN [^-]+-----/, "")
    .replace(/-----END [^-]+-----/, "")
    .replace(/\s+/g, "")
  return new Uint8Array(Buffer.from(b64, "base64"))
}

export function createApnsAdapter(cfg: ApnsConfig, h2post: H2Post = defaultH2Post): PlatformPushAdapter {
  let cachedJwt: string | null = null
  let cachedAt = 0
  let signingKey: Promise<CryptoKey> | null = null

  function importKey(): Promise<CryptoKey> {
    // Import once; reuse the (non-extractable) key for every signing call.
    // Only cache on success so a transient failure doesn't poison the adapter
    // for the rest of the process — the next send retries the import.
    if (!signingKey) {
      signingKey = crypto.subtle
        .importKey("pkcs8", pemToDer(cfg.keyP8), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"])
        .catch((err) => {
          signingKey = null
          throw err
        })
    }
    return signingKey
  }

  async function providerJwt(): Promise<string> {
    const now = Date.now()
    if (cachedJwt && now - cachedAt < JWT_TTL_MS) return cachedJwt

    const header = { alg: "ES256", kid: cfg.keyId, typ: "JWT" }
    const claims = { iss: cfg.teamId, iat: Math.floor(now / 1000) }
    const signingInput = `${base64urlJson(header)}.${base64urlJson(claims)}`

    const key = await importKey()
    // WebCrypto ECDSA returns the raw r||s concatenation (64 bytes for P-256),
    // which is exactly the form JWS ES256 expects — no DER re-encoding needed.
    const sig = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      new TextEncoder().encode(signingInput),
    )
    const jwt = `${signingInput}.${base64url(new Uint8Array(sig))}`

    cachedJwt = jwt
    cachedAt = now
    return jwt
  }

  return {
    // `payload` is the relay's already-encrypted blob (`{ ciphertext }`), not the
    // broker-side PushPayload — we only reuse PlatformPushAdapter's shape here.
    async send(token, payload: any) {
      // A bad/misconfigured key shouldn't crash transport — log it and fall back
      // to an empty bearer (APNs will 403, mapped transient). A real `.p8` signs.
      const jwt = await providerJwt().catch((err) => {
        log.error("apns_jwt_sign_failed", { err: err?.message ?? String(err) })
        return ""
      })
      const host = cfg.sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com"
      const body = JSON.stringify({
        aps: { alert: { title: "supermux", body: "" }, "mutable-content": 1 },
        data: payload.ciphertext,
      })
      const res = await h2post({
        host,
        path: `/3/device/${token}`,
        headers: {
          authorization: `bearer ${jwt}`,
          "apns-topic": cfg.bundleId,
          "apns-push-type": "alert",
        },
        body,
      })
      if (res.status === 200) return { ok: true }
      // 410 Unregistered (and 400 BadDeviceToken) mean the token is dead and
      // must be dropped; everything else is treated as transient.
      const gone = res.status === 410 || /Unregistered|BadDeviceToken/.test(res.body)
      return { ok: false, gone }
    },
  }
}

const H2_TIMEOUT_MS = 30_000

export const defaultH2Post: H2Post = (o) =>
  new Promise((resolve, reject) => {
    const session = http2.connect(`https://${o.host}`)
    let settled = false
    // Forcibly tear the session down on every terminal path so a stalled or
    // black-holed connection can't leak a socket or wedge the promise.
    const done = (fn: () => void) => {
      if (settled) return
      settled = true
      session.destroy()
      fn()
    }

    session.on("error", (err) => done(() => reject(err)))

    const req = session.request({
      ":method": "POST",
      ":path": o.path,
      "content-type": "application/json",
      "content-length": Buffer.byteLength(o.body),
      ...o.headers,
    })
    req.setTimeout(H2_TIMEOUT_MS, () => done(() => reject(new Error("apns request timed out"))))

    let status = 0
    const chunks: Buffer[] = []
    req.on("response", (headers) => {
      status = Number(headers[":status"]) || 0
    })
    req.on("data", (chunk) => chunks.push(chunk as Buffer))
    req.on("error", (err) => done(() => reject(err)))
    req.on("end", () => done(() => resolve({ status, body: Buffer.concat(chunks).toString("utf8") })))

    req.end(o.body)
  })
