// Auth cookie + same-origin helpers. Pure string/Request logic, no I/O — the
// HttpOnly cmux_token cookie is the browser-only credential (no localStorage,
// no Bearer, no ?t=). Native clients authenticate with Authorization: Bearer.
// See docs/superpowers/specs/2026-05-31-auth-cookie-hardening-design.md.
import { parseCookie } from "./proxy"

export const AUTH_COOKIE = "cmux_token"

interface CookieOpts {
  publicUrl: string
  proxyBaseDomain?: string
}

function hostOf(publicUrl: string): string {
  try {
    return new URL(publicUrl).hostname
  } catch {
    return ""
  }
}

function isLocalhost(host: string): boolean {
  return host === "localhost" || host === "127.0.0.1" || host.endsWith(".localhost")
}

// Shared attribute tail for set + clear so they always agree on scope.
function attrs(o: CookieOpts): string {
  const host = hostOf(o.publicUrl)
  // Secure unless plain-http on a non-localhost host (a LAN dev box over http
  // would otherwise drop the cookie). localhost is a browser secure-context.
  const secure = o.publicUrl.startsWith("https:") || isLocalhost(host)
  // A Domain=.<base> cookie spans the app + every proxy subdomain with one
  // credential. Only when the app host is actually within that base domain.
  const domain =
    o.proxyBaseDomain && (host === o.proxyBaseDomain || host.endsWith(`.${o.proxyBaseDomain}`))
      ? `; Domain=.${o.proxyBaseDomain}`
      : ""
  return `Path=/; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}${domain}`
}

export function buildAuthCookie(token: string, o: CookieOpts): string {
  return `${AUTH_COOKIE}=${token}; ${attrs(o)}; Max-Age=31536000`
}

export function buildClearCookie(o: CookieOpts): string {
  return `${AUTH_COOKIE}=; ${attrs(o)}; Max-Age=0`
}

export function cookieToken(req: Request): string {
  return parseCookie(req.headers.get("cookie"), AUTH_COOKIE) ?? ""
}

// Native clients (no browser, no ambient cookie) present the SAME device token
// as an Authorization: Bearer header. Browsers cannot set this header on a WS
// upgrade, so the cookie path is unchanged for the PWA.
export function bearerToken(req: Request): string {
  const h = req.headers.get("authorization") ?? ""
  const m = /^Bearer\s+(.+)$/i.exec(h.trim())
  return m ? m[1]!.trim() : ""
}

// Cookie first (PWA), then bearer (native). Cookie wins if both present.
export function authToken(req: Request): string {
  return cookieToken(req) || bearerToken(req)
}

// True when the request authenticated via bearer (a non-browser client with no
// ambient credential) — used to skip the same-origin CSRF guard, which only
// matters for cookie-bearing browsers.
export function authedViaBearer(req: Request): boolean {
  return !cookieToken(req) && bearerToken(req) !== ""
}

/**
 * The one cross-site guard every request passes before it is routed. A request that carries the
 * app's cookie is the browser acting for the user, so it must come from a page the app trusts.
 * SameSite=Lax is not that line on its own: WebKit attaches the cookie even from a sandboxed srcdoc
 * frame inside the app (measured 2026-11-08), and a proxied page on `<slug>.<base>` gets the
 * `Domain=.<base>` cookie by design. Returns why the request is refused, or undefined.
 *   - `Origin: null` is an opaque origin: a sandboxed frame or a data: page.
 *   - `Sec-Fetch-Site: cross-site` is refused unless it is a top-level GET/HEAD navigation (a link).
 *   - The app's own WebSockets open only from `appOrigins`. A proxied app's sockets (`proxied`) come
 *     from its own origin, so only the two rules above apply to them.
 * Bearer clients (the native apps) and requests without the cookie have no ambient session to ride.
 */
export function crossSiteRefusal(req: Request, o: { appOrigins: (string | undefined)[]; proxied: boolean }): string | undefined {
  if (!cookieToken(req)) return undefined
  const origin = req.headers.get("origin")
  if (origin?.trim().toLowerCase() === "null") return "opaque origin"
  if (req.headers.get("sec-fetch-site") === "cross-site" && !isTopLevelNavigation(req)) return "cross-site request"
  const websocket = req.headers.get("upgrade")?.toLowerCase() === "websocket"
  if (websocket && !o.proxied && !sameOriginOk(req, ...o.appOrigins)) return "socket from another origin"
  return undefined
}

function isTopLevelNavigation(req: Request): boolean {
  const dest = req.headers.get("sec-fetch-dest")
  return req.headers.get("sec-fetch-mode") === "navigate" && (dest === null || dest === "document") &&
    (req.method === "GET" || req.method === "HEAD")
}

// CSRF guard (defense-in-depth). SameSite=Lax is the primary defense — it stops
// the browser from attaching cmux_token to a cross-site POST at all, so a forged
// request arrives with no cookie and fails auth anyway. On top of that: if an
// Origin header IS present (browsers always send it on cross-origin — and modern
// ones on same-origin — POSTs), it MUST match the app origin. A missing Origin
// means a non-browser client (curl/scripts), which has no ambient cookie to
// abuse, so it's allowed.
export function sameOriginOk(req: Request, ...publicUrls: (string | undefined)[]): boolean {
  const origin = req.headers.get("origin")
  if (!origin) return true
  try {
    const requestOrigin = new URL(origin).origin
    return publicUrls.some((publicUrl) => {
      if (!publicUrl) return false
      try {
        return requestOrigin === new URL(publicUrl).origin
      } catch {
        return false
      }
    })
  } catch {
    return false
  }
}
