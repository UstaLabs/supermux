// #116: our own client for Certum's SimplySign cloud, the one piece of code our signing credentials pass through
// (Ahmet, 2026-11-10: no third-party code in the signing path). Certum documents no API for this; the protocol was
// read from two open implementations, used only as references: Le-Syl21/ssign and IvanHanloth/super-simply-sign.
//
//   login         GET  /idp/oauth2.0/authorize → (redirects) → the CAS login page (hidden `execution`, `lt`)
//                 POST /idp/login  username=<e-mail> password=<one-time code> → redirect carrying ?code=
//                 POST /idp/oauth2.0/accessToken?…&code=… → { access_token }
//   tasks         POST …/tasks → 202 { "atom:link", "ping-after" } → poll the link until no link: that reply is the result
//   cards         POST /card/v1/cards/tasks → [ { cardno, label, pinrequired } ]
//   certificate   POST /card/v1/cards/<cardno>/certificates/tasks → multipart, part "certificate" (PEM)
//   signature     POST /card/v1/cards/<cardno>/certificates/signature  multipart: req {digests:[hex], digesttype:SHA256}
//                 + the certificate as returned → [ { <digest hex>: <signature hex> } ] (RSA PKCS#1 v1.5 over the digest)
//
// Every request goes to Certum's one host: a redirect or a poll link anywhere else is an error, never a hop. Nothing is
// logged or written here, and no error carries the e-mail, the code, the authorization code, the token or a page body.

/** One HTTP reply, as the seam returns it. */
export interface Reply {
  status: number
  headers: Record<string, string>
  body: Buffer
}

/** The HTTP seam: one request, never following redirects (tests fake it; [httpFetch] is the real one). */
export type Fetch = (url: string | URL, init: { method?: string; headers?: Record<string, string>; body?: Uint8Array | string }) => Promise<Reply>

/** Certum's endpoints. The OAuth client is SimplySign Desktop's public client (the same in every install), not ours. */
export const CERTUM = {
  origin: "https://cloudsign.webnotarius.pl",
  authorizeUrl: "https://cloudsign.webnotarius.pl/idp/oauth2.0/authorize",
  loginUrl: "https://cloudsign.webnotarius.pl/idp/login",
  tokenUrl: "https://cloudsign.webnotarius.pl/idp/oauth2.0/accessToken",
  scope: "https://cloudsign.webnotarius.pl/idp/oauth2.0/profile",
  redirectUri: "https://cloudsign.webnotarius.pl/redirect",
  clientId: "44rvDKKEWY53a7xBeF5w",
  clientSecret: "BRSE2u2nY3p3m77QHTt8",
} as const

export class CloudError extends Error {
  /** The identity provider turned the one-time code down (wrong, or already spent). */
  readonly refused: boolean
  constructor(message: string, refused = false) {
    super(message)
    this.name = "CloudError"
    this.refused = refused
  }
}

export interface Session {
  readonly token: string
}

export interface Card {
  readonly serial: string
  /** The signing certificate exactly as the cloud returned it (PEM); the sign request wants it back as is. */
  readonly certificatePem: Buffer
}

/** The real seam: fetch, redirects left to us, a hard timeout. */
export const httpFetch: Fetch = async (url, init) => {
  const response = await fetch(url, { method: init.method ?? "GET", headers: init.headers, body: init.body, redirect: "manual", signal: AbortSignal.timeout(60_000) })
  const headers: Record<string, string> = {}
  response.headers.forEach((value, key) => { headers[key.toLowerCase()] = value })
  const cookies = (response.headers as unknown as { getSetCookie?: () => string[] }).getSetCookie?.()
  if (cookies?.length) headers["set-cookie"] = cookies.join("\n")
  return { status: response.status, headers, body: Buffer.from(await response.arrayBuffer()) }
}

const header = (reply: Reply, name: string): string | undefined => {
  const key = Object.keys(reply.headers).find((k) => k.toLowerCase() === name)
  return key === undefined ? undefined : reply.headers[key]
}

/** [link] resolved against Certum's origin; anything on another host is refused. */
function onCertum(link: string, base: string | URL = CERTUM.origin): URL {
  let url: URL
  try {
    url = new URL(link, base)
  } catch {
    throw new CloudError("the cloud answered with an invalid link")
  }
  if (url.origin !== CERTUM.origin) throw new CloudError(`refused a link to another host (${url.host})`)
  return url
}

/** The cookies a login page set, per name, sent back on the next request. */
class Cookies {
  private readonly jar = new Map<string, string>()
  take(reply: Reply): void {
    for (const line of (header(reply, "set-cookie") ?? "").split("\n")) {
      const pair = line.split(";")[0]!.trim()
      const eq = pair.indexOf("=")
      if (eq > 0) this.jar.set(pair.slice(0, eq), pair.slice(eq + 1))
    }
  }
  header(): Record<string, string> {
    return this.jar.size ? { cookie: [...this.jar].map(([k, v]) => `${k}=${v}`).join("; ") } : {}
  }
}

const ENTITIES: Record<string, string> = { "&amp;": "&", "&quot;": '"', "&#39;": "'", "&#x27;": "'", "&lt;": "<", "&gt;": ">" }

/** The value of `<input name="[name]" value="…">` in a page, either attribute order. */
export function hiddenInput(html: string, name: string): string | null {
  const m = new RegExp(`<input[^>]*\\bname=["']${name}["'][^>]*\\bvalue=["']([^"']*)["']`, "is").exec(html)
    ?? new RegExp(`<input[^>]*\\bvalue=["']([^"']*)["'][^>]*\\bname=["']${name}["']`, "is").exec(html)
  return m ? m[1]!.replace(/&(?:amp|quot|#39|#x27|lt|gt);/g, (e) => ENTITIES[e] ?? e) : null
}

/**
 * Follow redirects by hand from [first] (each on Certum's host, cookies kept), stopping early when [stop] says the
 * next hop's URL is what we wanted (it is then never requested). Returns the last reply and its URL.
 */
async function follow(
  fetch: Fetch, cookies: Cookies, first: URL, init: { method: string; headers?: Record<string, string>; body?: string },
  stop: (next: URL) => boolean = () => false,
): Promise<{ reply: Reply; url: URL; stoppedAt?: URL }> {
  let url = first
  let request = init
  for (let hop = 0; hop < 20; hop++) {
    const reply = await fetch(url, { ...request, headers: { ...request.headers, ...cookies.header() } })
    cookies.take(reply)
    const location = reply.status >= 300 && reply.status < 400 ? header(reply, "location") : undefined
    if (!location) return { reply, url }
    const next = onCertum(location, url)
    if (stop(next)) return { reply, url, stoppedAt: next }
    url = next
    request = { method: "GET", headers: { accept: "text/html,*/*" } }
  }
  throw new CloudError("the login went through too many redirects")
}

/** Log in with the account's e-mail and a current one-time code (there is no password); returns the bearer token. */
export async function login(fetch: Fetch, email: string, code: string): Promise<Session> {
  const cookies = new Cookies()
  const authorize = new URL(CERTUM.authorizeUrl)
  authorize.search = new URLSearchParams({ response_type: "code", client_id: CERTUM.clientId, redirect_uri: CERTUM.redirectUri, scope: CERTUM.scope, api_key: "" }).toString()
  const page = await follow(fetch, cookies, authorize, { method: "GET", headers: { accept: "text/html,*/*" } })
  if (page.reply.status !== 200) throw new CloudError(`the login page answered HTTP ${page.reply.status}`)
  const html = page.reply.body.toString("utf8")
  const execution = hiddenInput(html, "execution")
  if (!execution) throw new CloudError("the login page has no CAS form: has SimplySign's login changed?")

  const loginUrl = new URL(CERTUM.loginUrl)
  const service = page.url.searchParams.get("service")
  if (service) loginUrl.searchParams.set("service", service)
  const form = new URLSearchParams({ username: email, password: code, execution, _eventId: "submit", geolocation: "", submit: "LOGIN", lt: hiddenInput(html, "lt") ?? "" })
  const redirect = new URL(CERTUM.redirectUri)
  const isRedirect = (u: URL) => u.origin === redirect.origin && u.pathname === redirect.pathname && u.searchParams.has("code")
  const sent = await follow(fetch, cookies, loginUrl, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", accept: "text/html,*/*" }, body: form.toString(),
  }, isRedirect)
  const authCode = sent.stoppedAt?.searchParams.get("code")
  if (!authCode) {
    if (/class=["'][^"']*\berrors?\b/i.test(sent.reply.body.toString("utf8"))) {
      throw new CloudError("the login was refused: the one-time code was wrong or already used, or the e-mail is not the account's", true)
    }
    throw new CloudError(`the login gave no authorization code (HTTP ${sent.reply.status})`)
  }

  const tokenUrl = new URL(CERTUM.tokenUrl)
  tokenUrl.search = new URLSearchParams({
    client_id: CERTUM.clientId, client_secret: CERTUM.clientSecret, scope: CERTUM.scope, code: authCode, redirect_uri: CERTUM.redirectUri, grant_type: "authorization_code",
  }).toString()
  const tokenReply = await fetch(tokenUrl, { method: "POST", headers: { accept: "application/json" } })
  let token: unknown
  try {
    token = (JSON.parse(tokenReply.body.toString("utf8")) as { access_token?: unknown }).access_token
  } catch {
    token = undefined
  }
  if (tokenReply.status !== 200 || typeof token !== "string" || token.length === 0) throw new CloudError(`the token exchange failed (HTTP ${tokenReply.status})`)
  return { token }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** The task protocol: send [init] to [path], then follow `atom:link` (on Certum's host) until a reply carries none. */
async function runTask(fetch: Fetch, session: Session, path: string, init: { headers?: Record<string, string>; body?: Uint8Array | string }): Promise<Reply> {
  const auth = { accept: "multipart/form-data, application/json", authorization: `Bearer ${session.token}` }
  const deadline = Date.now() + 5 * 60_000
  let reply = await fetch(onCertum(path), { method: "POST", headers: { ...init.headers, ...auth }, body: init.body })
  for (let poll = 0; poll < 200; poll++) {
    if (reply.status >= 400) throw new CloudError(`a cloud request failed (HTTP ${reply.status} at ${path})`)
    const type = header(reply, "content-type") ?? ""
    let next: URL | null = null
    let wait = 0
    if (!type.includes("multipart")) {
      let value: unknown
      try { value = JSON.parse(reply.body.toString("utf8")) } catch { value = undefined }
      if (value && typeof value === "object" && !Array.isArray(value)) {
        const v = value as Record<string, unknown>
        if (v["state"] === "failed") throw new CloudError(`the cloud refused the request (${path})`)
        if (typeof v["atom:link"] === "string") {
          next = onCertum(v["atom:link"])
          wait = typeof v["ping-after"] === "number" ? v["ping-after"] : 0
        }
      }
      const location = header(reply, "location")
      if (!next && reply.status >= 300 && reply.status < 400 && location) next = onCertum(location)
    }
    if (!next) return reply
    if (Date.now() > deadline) throw new CloudError(`the cloud did not finish in time (${path})`)
    await sleep(Math.min(Math.max(wait, 0), 2000))
    reply = await fetch(next, { method: "GET", headers: auth })
  }
  throw new CloudError(`the cloud did not finish (${path})`)
}

/** The bytes of the multipart part named [name]. */
function multipartPart(reply: Reply, name: string): Buffer {
  const boundary = /boundary="?([^";]+)"?/i.exec(header(reply, "content-type") ?? "")?.[1]
  if (!boundary) throw new CloudError("the certificate reply is not multipart")
  const body = reply.body
  const delimiter = Buffer.from(`--${boundary}`, "latin1")
  let at = body.indexOf(delimiter)
  while (at >= 0) {
    const start = at + delimiter.length
    const end = body.indexOf(delimiter, start)
    if (end < 0) break
    const part = body.subarray(start, end)
    const split = part.indexOf("\r\n\r\n")
    if (split >= 0 && new RegExp(`name="${name}"`, "i").test(part.subarray(0, split).toString("latin1"))) {
      let content = part.subarray(split + 4)
      if (content.subarray(-2).toString("latin1") === "\r\n") content = content.subarray(0, -2)
      return Buffer.from(content)
    }
    at = end
  }
  throw new CloudError(`the reply has no "${name}" part`)
}

/** The account's signing card (its one card) and its certificate. */
export async function signingCard(fetch: Fetch, session: Session): Promise<Card> {
  const list = await runTask(fetch, session, "/card/v1/cards/tasks", { headers: { "content-type": "application/json" } })
  let cards: unknown
  try { cards = JSON.parse(list.body.toString("utf8")) } catch { cards = undefined }
  if (!Array.isArray(cards) || cards.length === 0) throw new CloudError("the account has no signing card")
  const card = cards[0] as Record<string, unknown>
  const serial = typeof card["cardno"] === "string" ? card["cardno"] : ""
  if (!/^[A-Za-z0-9._-]{1,128}$/.test(serial)) throw new CloudError("the signing card's number has an unexpected form")
  if (card["pinrequired"] === true) throw new CloudError("the signing card wants a PIN, which this client cannot give")
  const reply = await runTask(fetch, session, `/card/v1/cards/${encodeURIComponent(serial)}/certificates/tasks`, { headers: { "content-type": "application/json" } })
  return { serial, certificatePem: multipartPart(reply, "certificate") }
}

/** Ask the cloud to sign one SHA-256 [digest] with [card]'s key; returns the RSA PKCS#1 v1.5 signature. */
export async function requestSignature(fetch: Fetch, session: Session, card: Card, digest: Buffer): Promise<Buffer> {
  if (digest.length !== 32) throw new CloudError("only a SHA-256 digest (32 bytes) can be signed")
  const hex = digest.toString("hex")
  const boundary = `----supermux${crypto.randomUUID().replaceAll("-", "")}`
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="req"\r\nContent-Type: application/json;charset=UTF-8\r\n\r\n`, "latin1"),
    Buffer.from(JSON.stringify({ digests: [hex], digesttype: "SHA256" })),
    Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="certificate"; filename="blob"\r\nContent-Type: application/octet-stream\r\n\r\n`, "latin1"),
    card.certificatePem,
    Buffer.from(`\r\n--${boundary}--\r\n`, "latin1"),
  ])
  const reply = await runTask(fetch, session, `/card/v1/cards/${encodeURIComponent(card.serial)}/certificates/signature`, {
    headers: { "content-type": `multipart/form-data; boundary=${boundary}` }, body,
  })
  let value: unknown
  try { value = JSON.parse(reply.body.toString("utf8")) } catch { value = undefined }
  const first = Array.isArray(value) ? (value[0] as Record<string, unknown> | undefined) : undefined
  const signature = first ? Object.entries(first).find(([d]) => d.toLowerCase() === hex)?.[1] : undefined
  if (typeof signature !== "string" || !/^(?:[0-9a-fA-F]{2})+$/.test(signature)) throw new CloudError("the cloud returned no signature for the digest")
  const bytes = Buffer.from(signature, "hex")
  if (bytes.length < 256) throw new CloudError(`the cloud's signature is too short (${bytes.length} bytes)`)
  return bytes
}
