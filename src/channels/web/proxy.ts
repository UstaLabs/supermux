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
        if (/^[/?#]/.test(rest)) return rest.startsWith("/") ? rest : "/" + rest
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
  if (path.startsWith("/") && !path.startsWith("//")) {
    // Idempotent: an already-prefixed path (e.g. a base-aware upstream) is left as-is.
    if (path === prefix || path.startsWith(prefix + "/")) return path
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
      if (orig === prefix || orig.startsWith(prefix + "/")) return `;${sp}Path=${orig}`
      const np = orig === "/" ? `${prefix}/` : `${prefix}${orig.startsWith("/") ? "" : "/"}${orig}`
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

// --- Proxy handler ---

export async function handleProxyRequest(
  req: Request,
  upstream: { port: number; sessionName: string },
  pathOpts?: { prefix: string; upstreamPath: string },
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
      signal: AbortSignal.timeout(30_000),
    })
  } catch (err: any) {
    const isTimeout =
      err?.name === "TimeoutError" ||
      err?.name === "AbortError" ||
      err?.code === "ETIMEDOUT"

    if (isTimeout) {
      log.warn("proxy.timeout", { session: upstream.sessionName, port: upstream.port })
      return new Response(
        `<html><body><h1>504 Gateway Timeout</h1><p>App on port ${upstream.port} timed out.</p></body></html>`,
        { status: 504, headers: { "content-type": "text/html" } },
      )
    }

    log.warn("proxy.upstream_error", {
      session: upstream.sessionName,
      port: upstream.port,
      err: err?.message ?? String(err),
    })
    return new Response(
      `<html><body><h1>502 Bad Gateway</h1><p>App on port ${upstream.port} is not responding.</p></body></html>`,
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

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    statusText: upstreamRes.statusText,
    headers: responseHeaders,
  })
}
