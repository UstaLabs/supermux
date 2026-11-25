import { makeLogger } from "../../shared/log"

const log = makeLogger("proxy")

// --- Subdomain extraction ---

/**
 * Extracts the subdomain part from a Host header given a base domain.
 * Returns null if:
 *   - host is the base domain itself
 *   - host is the main domain (mainHost)
 *   - host doesn't end with `.{baseDomain}`
 */
export function extractSubdomain(
  host: string,
  baseDomain: string,
  mainHost?: string,
): string | null {
  // Strip port if present
  const bareHost = host.replace(/:\d+$/, "")

  // Reject if host IS the base domain
  if (bareHost === baseDomain) return null

  // Reject if host IS the main domain
  if (mainHost && bareHost === mainHost.replace(/:\d+$/, "")) return null

  // Must end with .{baseDomain}
  const suffix = `.${baseDomain}`
  if (!bareHost.endsWith(suffix)) return null

  const sub = bareHost.slice(0, bareHost.length - suffix.length)
  // Sanity: subdomain must be non-empty and contain no dots (single-level)
  if (!sub || sub.includes(".")) return null

  return sub
}

// --- URL builders ---

export function buildUpstreamUrl(port: number, pathAndQuery: string): string {
  return `http://127.0.0.1:${port}${pathAndQuery}`
}

export function buildUpstreamWsUrl(port: number, pathAndQuery: string): string {
  return `ws://127.0.0.1:${port}${pathAndQuery}`
}

// --- Path-based proxy: /p/<slug>/... ---

/**
 * Matches a path-based proxy URL. Returns the slug and `rest` (the path AFTER
 * the `/p/<slug>` prefix), or null when `pathname` is not a proxy path.
 *   /p/app          -> { slug: "app", rest: null }   (caller 301s to add "/")
 *   /p/app/         -> { slug: "app", rest: "/" }
 *   /p/app/assets/x -> { slug: "app", rest: "/assets/x" }
 *   /pair, /proxies -> null
 */
export function matchProxyPath(pathname: string): { slug: string; rest: string | null } | null {
  const m = pathname.match(/^\/p\/([^/?#]+)(\/.*)?$/)
  if (!m) return null
  return { slug: m[1]!, rest: m[2] ?? null }
}

/**
 * By-port previews: a digits-only slug is a PORT (`/p/5173/`, `5173.<base>`), never a name (Registry.addProxy refuses
 * digits-only names). Returns undefined for a slug that is a name, null for a digits-only slug that is not a usable
 * port (0, above 65535, a leading zero: one port gets one URL), else the port.
 */
export function portFromSlug(slug: string): number | null | undefined {
  if (!/^[0-9]+$/.test(slug)) return undefined
  if (!/^[1-9][0-9]{0,4}$/.test(slug)) return null
  const port = Number(slug)
  return port <= 65535 ? port : null
}

/**
 * Builds a proxy's public URL. Subdomain mode (unchanged) when a base domain is
 * set; path mode under the broker's own public URL otherwise.
 */
export function buildProxyPublicUrl(
  slug: string,
  opts: { baseDomain?: string; publicUrl?: string },
): string {
  if (opts.baseDomain) return `https://${slug}.${opts.baseDomain}`
  const base = (opts.publicUrl ?? "").replace(/\/+$/, "")
  return `${base}/p/${slug}/`
}

/**
 * If `loc` is an absolute URL on the loopback upstream (`127.0.0.1:<port>` or
 * `localhost:<port>`), returns its path + query + hash; otherwise null. Apps that
 * build absolute URLs from the Host header produce these, since the proxy sends
 * `host: 127.0.0.1:<port>` upstream.
 */
export function stripUpstreamOrigin(loc: string, upstreamHost: string): string | null {
  const port = upstreamHost.slice(upstreamHost.lastIndexOf(":"))
  for (const scheme of ["http://", "https://"]) {
    for (const host of [upstreamHost, `localhost${port}`]) {
      const origin = scheme + host
      if (loc.startsWith(origin)) {
        const rest = loc.slice(origin.length)
        if (rest === "") return "/"
        if (/^[/?#]/.test(rest)) return rest.startsWith("/") ? rest : "/" + rest // platform-ok: a URL path, / by definition
      }
    }
  }
  return null
}

/**
 * Subdomain mode: point a redirect at the upstream origin back at the public
 * origin. Reduced to a path, the Location resolves against whatever public
 * host the browser used. Every other Location passes through untouched.
 */
export function rewriteSubdomainLocation(loc: string, upstreamHost: string): string {
  return stripUpstreamOrigin(loc, upstreamHost) ?? loc
}

/**
 * Path mode only: re-add the proxy prefix to a redirect Location. Absolute paths
 * (single leading slash) and absolute URLs pointing back at the loopback upstream
 * are rewritten; relative, protocol-relative, and off-host Locations pass through.
 */
export function rewriteLocation(loc: string, prefix: string, upstreamHost: string): string {
  const path = stripUpstreamOrigin(loc, upstreamHost) ?? loc
  if (path.startsWith("/") && !path.startsWith("//")) { // platform-ok: a URL path, / by definition
    // Idempotent: an already-prefixed path (e.g. a base-aware upstream) is left as-is.
    if (path === prefix || path.startsWith(prefix + "/")) return path // platform-ok: a URL path, / by definition
    return prefix + path
  }
  return loc
}

/**
 * Path mode only: scope an upstream Set-Cookie to the proxy prefix so each app's
 * cookies stay under its own /p/<slug>/ path and don't leak across apps sharing
 * the broker origin.
 */
export function rewriteSetCookiePath(setCookie: string, prefix: string): string {
  if (/;\s*path=/i.test(setCookie)) {
    return setCookie.replace(/;(\s*)path=([^;]*)/i, (_m, sp: string, p: string) => {
      const orig = p.trim() || "/"
      // Idempotent: a cookie already scoped under the prefix is left as-is.
      if (orig === prefix || orig.startsWith(prefix + "/")) return `;${sp}Path=${orig}` // platform-ok: a cookie's URL path, / by definition
      const np = orig === "/" ? `${prefix}/` : `${prefix}${orig.startsWith("/") ? "" : "/"}${orig}` // platform-ok: a cookie's URL path, / by definition
      return `;${sp}Path=${np}`
    })
  }
  return `${setCookie}; Path=${prefix}/`
}

// --- Cookie parsing ---

export function parseCookie(cookieHeader: string | null, name: string): string | null {
  if (!cookieHeader) return null
  for (const part of cookieHeader.split(";")) {
    const [rawKey, ...rest] = part.split("=")
    const key = rawKey?.trim()
    if (key === name) {
      return rest.join("=").trim()
    }
  }
  return null
}

// --- Headers to strip from forwarded requests ---
const STRIP_REQUEST_HEADERS = new Set(["cf-connecting-ip", "cf-ray", "cf-visitor"])

// --- Headers to strip from upstream responses ---
const STRIP_RESPONSE_HEADERS = new Set(["transfer-encoding"])

/** The Cookie header without the named cookies, or null when nothing is left. */
export function withoutCookies(header: string, names: string[]): string | null {
  const kept = header.split(";").map((p) => p.trim()).filter((p) => {
    if (!p) return false
    const eq = p.indexOf("=")
    return !names.includes((eq < 0 ? p : p.slice(0, eq)).trim())
  })
  return kept.length ? kept.join("; ") : null
}

/** What the proxied app must never see: supermux's own cookies, and a bearer that is one of its device tokens. */
export interface OwnCredentials { cookies: string[]; isOwnBearer?: (token: string) => boolean }

/** The applet route's differences (#117). */
export interface ProxyExtras {
  /** Upstream time limit; null = none, the call lives as long as the client's request (SSE, long polls). 30 s by default. */
  timeoutMs?: number | null
  /** Request headers whose name starts with one of these are dropped first (a caller can't forge X-Mux-*). */
  stripRequestPrefixes?: string[]
  /** Set on the forwarded request after stripping (the signed X-Mux-Via). */
  setHeaders?: Record<string, string>
  /** Who the error pages name ("The applet notes"); "App on port <n>" by default. */
  label?: string
}

// --- Proxy handler ---

export async function handleProxyRequest(
  req: Request,
  upstream: { port: number; sessionName: string },
  pathOpts?: { prefix: string; upstreamPath: string },
  own?: OwnCredentials,
  extras?: ProxyExtras,
): Promise<Response> {
  const url = new URL(req.url)
  const pathAndQuery = pathOpts ? pathOpts.upstreamPath : url.pathname + (url.search || "")
  const upstreamUrl = buildUpstreamUrl(upstream.port, pathAndQuery)

  // Build forwarded headers
  const forwardedHeaders = new Headers(req.headers)
  forwardedHeaders.set("host", `127.0.0.1:${upstream.port}`)
  for (const h of STRIP_REQUEST_HEADERS) {
    forwardedHeaders.delete(h)
  }
  // An agent's dev server must not receive the user's device token: it would be the whole API, from anywhere.
  if (own) {
    const cookie = forwardedHeaders.get("cookie")
    if (cookie) {
      const kept = withoutCookies(cookie, own.cookies)
      if (kept) forwardedHeaders.set("cookie", kept)
      else forwardedHeaders.delete("cookie")
    }
    const bearer = /^Bearer\s+(.+)$/i.exec(forwardedHeaders.get("authorization")?.trim() ?? "")?.[1]?.trim()
    if (bearer && own.isOwnBearer?.(bearer)) forwardedHeaders.delete("authorization")
  }
  if (extras?.stripRequestPrefixes?.length) {
    const prefixes = extras.stripRequestPrefixes.map((p) => p.toLowerCase())
    for (const name of [...forwardedHeaders.keys()]) {
      if (prefixes.some((p) => name.toLowerCase().startsWith(p))) forwardedHeaders.delete(name)
    }
  }
  for (const [k, v] of Object.entries(extras?.setHeaders ?? {})) forwardedHeaders.set(k, v)
  const timeoutMs = extras?.timeoutMs === undefined ? 30_000 : extras.timeoutMs

  log.debug("proxy.forward", {
    session: upstream.sessionName,
    port: upstream.port,
    method: req.method,
    path: pathAndQuery,
  })

  let upstreamRes: Response
  try {
    upstreamRes = await fetch(upstreamUrl, {
      method: req.method,
      headers: forwardedHeaders,
      body: req.body,
      // Never follow redirects here: the browser must see the 3xx (so its URL
      // and Set-Cookie update), and a streamed POST body can't be replayed.
      redirect: "manual",
      // Relay the upstream bytes as-is. Bun inflates by default but the
      // content-encoding header is still copied below, so the browser would
      // try to inflate plain bytes and render a blank page.
      decompress: false,
      // @ts-ignore — Bun supports signal-based timeout via AbortSignal.timeout
      signal: timeoutMs === null ? req.signal : AbortSignal.timeout(timeoutMs),
    })
  } catch (err: any) {
    const isTimeout =
      err?.name === "TimeoutError" ||
      err?.name === "AbortError" ||
      err?.code === "ETIMEDOUT"

    if (isTimeout) {
      log.warn("proxy.timeout", { session: upstream.sessionName, port: upstream.port })
      return new Response(
        `<html><body><h1>504 Gateway Timeout</h1><p>${extras?.label ?? `App on port ${upstream.port}`} timed out.</p></body></html>`,
        { status: 504, headers: { "content-type": "text/html" } },
      )
    }

    log.warn("proxy.upstream_error", {
      session: upstream.sessionName,
      port: upstream.port,
      err: err?.message ?? String(err),
    })
    return new Response(
      `<html><body><h1>502 Bad Gateway</h1><p>${extras?.label ?? `App on port ${upstream.port}`} is not responding.</p></body></html>`,
      { status: 502, headers: { "content-type": "text/html" } },
    )
  }

  // Build response headers, stripping unwanted ones. Set-Cookie is copied one
  // entry at a time so several cookies never collapse into one joined value.
  const responseHeaders = new Headers()
  upstreamRes.headers.forEach((v, k) => {
    if (k !== "set-cookie" && !STRIP_RESPONSE_HEADERS.has(k)) responseHeaders.set(k, v)
  })
  const setCookies = upstreamRes.headers.getSetCookie()

  // Exposed ports are dev servers serving changing content. If the upstream
  // didn't set its own caching policy, force no-store so the fronting CDN
  // (Cloudflare) and browsers don't serve stale bundles.
  if (!responseHeaders.has("cache-control")) {
    responseHeaders.set("cache-control", "no-store")
  }

  // Path mode re-anchors redirects and cookies under the /p/<slug> prefix;
  // subdomain mode only maps upstream-origin redirects to the public origin.
  const upstreamHost = `127.0.0.1:${upstream.port}`
  const loc = responseHeaders.get("location")
  if (loc) {
    responseHeaders.set(
      "location",
      pathOpts ? rewriteLocation(loc, pathOpts.prefix, upstreamHost) : rewriteSubdomainLocation(loc, upstreamHost),
    )
  }
  for (const c of setCookies) {
    responseHeaders.append("set-cookie", pathOpts ? rewriteSetCookiePath(c, pathOpts.prefix) : c)
  }

  const ranged = await answerRange(req, upstreamRes, responseHeaders)
  if (ranged) return ranged

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    statusText: upstreamRes.statusText,
    headers: responseHeaders,
  })
}

/** The most a range answer carries at once: a media player asks again for the rest, as it does of any server. */
export const RANGE_PIECE_BYTES = 2 * 1024 * 1024

/** `bytes=a-b`, `bytes=a-` or `bytes=-n` against [size] → [first, last] inclusive, or null (several ranges, nonsense). */
export function parseByteRange(header: string | null, size: number): [number, number] | null {
  const m = /^\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*$/i.exec(header ?? "")
  if (!m || (m[1] === "" && m[2] === "") || size <= 0) return null
  if (m[1] === "") {
    const n = Math.min(Number(m[2]), size)
    return n > 0 ? [size - n, size - 1] : null
  }
  const first = Number(m[1])
  if (first >= size) return null
  const last = m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1)
  return last >= first ? [first, last] : null
}

/**
 * A range request the upstream answered with the whole file (Python's http.server and most dev servers ignore Range):
 * answered here instead, as a 206 of at most [RANGE_PIECE_BYTES] with Content-Range and Content-Length. WebKit (macOS,
 * iOS) plays a <video> or <audio> only from 206s, where Chromium takes a plain 200. Bun sends a proxied stream chunked,
 * losing its length, so the piece is read into memory: it goes out with its length. Null when there is nothing to do: a
 * request without a single byte range, an upstream that answered the range itself, or a body whose size or encoding
 * makes byte offsets meaningless.
 */
async function answerRange(req: Request, upstreamRes: Response, headers: Headers): Promise<Response | null> {
  if (req.method !== "GET" || upstreamRes.status !== 200 || !upstreamRes.body) return null
  if (headers.has("content-encoding") || headers.has("content-range")) return null
  const size = Number(headers.get("content-length"))
  if (!Number.isSafeInteger(size) || size <= 0) return null
  const wanted = req.headers.get("range")
  if (!wanted) return null
  const range = parseByteRange(wanted, size)
  if (!range) {
    await upstreamRes.body.cancel().catch(() => {})
    const h = new Headers(headers)
    h.set("content-range", `bytes */${size}`)
    h.delete("content-length")
    return new Response(null, { status: 416, headers: h })
  }
  const first = range[0]
  const last = Math.min(range[1], first + RANGE_PIECE_BYTES - 1)
  const piece = new Uint8Array(last - first + 1)
  const reader = upstreamRes.body.getReader()
  let at = 0
  let filled = 0
  try {
    while (filled < piece.length) {
      const { done, value } = await reader.read()
      if (done) break
      const end = at + value.byteLength
      if (end > first) {
        const from = Math.max(0, first - at)
        const take = value.subarray(from, Math.min(value.byteLength, from + piece.length - filled))
        piece.set(take, filled)
        filled += take.byteLength
      }
      at = end
    }
  } finally {
    await reader.cancel().catch(() => {})
  }
  if (filled < piece.length) return null
  const h = new Headers(headers)
  h.set("content-range", `bytes ${first}-${last}/${size}`)
  h.set("content-length", String(piece.length))
  h.set("accept-ranges", "bytes")
  return new Response(piece, { status: 206, headers: h })
}
