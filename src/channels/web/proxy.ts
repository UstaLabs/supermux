import { makeLogger } from "../../shared/log"

const log = makeLogger("proxy")

// --- Types ---

export type ProxyLookup = (domain: string) => { port: number; sessionName: string } | undefined
export type ProxyAuth = (req: Request) => boolean

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
): Promise<Response> {
  const url = new URL(req.url)
  const pathAndQuery = url.pathname + (url.search || "")
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

  // Build response headers, stripping unwanted ones
  const responseHeaders = new Headers(upstreamRes.headers)
  for (const h of STRIP_RESPONSE_HEADERS) {
    responseHeaders.delete(h)
  }

  // Exposed ports are dev servers serving changing content. If the upstream
  // didn't set its own caching policy, force no-store so the fronting CDN
  // (Cloudflare) and browsers don't serve stale bundles.
  if (!responseHeaders.has("cache-control")) {
    responseHeaders.set("cache-control", "no-store")
  }

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    statusText: upstreamRes.statusText,
    headers: responseHeaders,
  })
}
