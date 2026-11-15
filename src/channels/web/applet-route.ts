import { existsSync, realpathSync, statSync } from "fs"
import { connect } from "net"
import { isAbsolute, join, relative } from "path"
import { isAppletName, isOwnerOnly } from "../../core/applets/manifest"
import type { AppletService } from "../../core/applets/service"
import { handleProxyRequest, type OwnCredentials } from "./proxy"

/**
 * #117: /a/<name>/, an applet's route. Paired devices only; the broker serves a static applet's public/ itself and
 * proxies to a server applet's loopback $PORT. Whatever the caller sent as X-Mux-* is dropped, and supermux's own
 * credentials never reach the applet; it gets X-Mux-Via (who is asking: device:<id> or agent:<session>), X-Mux-Via-Sig
 * (hex HMAC-SHA256 of the X-Mux-Via value under the applet's $MUX_VIA_KEY) and X-Mux-Device-Name (unsigned, display
 * only). The proxy refuses ownerOnly paths to agents, but an agent on this host can reach the server's port directly,
 * so an applet's server must ALSO refuse ownerOnly requests without a valid signature over a device:… value. No
 * session is involved anywhere.
 */

/** /a/<name> → { name, rest: null } (301 to add "/"); /a/<name>/x → { name, rest: "/x" }; anything else null. */
export function matchAppletPath(pathname: string): { name: string; rest: string | null } | null {
  const m = pathname.match(/^\/a\/([^/?#]+)(\/.*)?$/)
  if (!m) return null
  return { name: m[1]!, rest: m[2] ?? null }
}

/**
 * Who is asking. Only a device is the owner: an agent's request never reaches an ownerOnly path. A device is named in
 * X-Mux-Via by a stable id (a prefix of its token's stored hash: names change and collide); its name goes along
 * unsigned, for display only.
 */
export type AppletCaller = { kind: "device"; id: string; name: string } | { kind: "agent"; session: string }

export function viaValue(c: AppletCaller): string {
  return c.kind === "device" ? `device:${c.id}` : `agent:${c.session}`
}

/** A device's id in X-Mux-Via: the first 16 hex characters of its token's stored SHA-256. */
export function deviceViaId(tokenHash: string): string {
  return tokenHash.slice(0, 16)
}

const page = (status: number, title: string, body: string) =>
  new Response(
    `<!DOCTYPE html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>` +
    `<body style="font-family:system-ui;background:#0a0a0a;color:#e5e5e5;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;padding:24px;box-sizing:border-box"><div style="max-width:32rem"><h1 style="font-size:1.1rem;font-weight:600">${title}</h1><p style="color:#a3a3a3;line-height:1.5">${body}</p></div></body>`,
    { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } },
  )

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!)

/** The file [rest] names inside [root], or null: never outside it (no `..`, no symlink out), a folder means its index.html. */
export function resolveStaticFile(root: string, rest: string): string | null {
  let rel: string
  try {
    rel = decodeURIComponent(rest)
  } catch {
    return null
  }
  if (rel.includes("\0") || rel.split(/[\\/]/).includes("..")) return null
  let realRoot: string
  try {
    realRoot = realpathSync(root)
  } catch {
    return null
  }
  let p = join(realRoot, rel)
  try {
    if (statSync(p).isDirectory()) p = join(p, "index.html")
    if (!existsSync(p)) return null
    const real = realpathSync(p)
    const inside = relative(realRoot, real)
    if (inside.startsWith("..") || isAbsolute(inside)) return null
    return statSync(real).isFile() ? real : null
  } catch {
    return null
  }
}

function serveStaticApplet(folder: string, rest: string, method: string): Response {
  if (method !== "GET" && method !== "HEAD") return page(405, "Not allowed", "This applet has only static files, so it can only be read.")
  const file = resolveStaticFile(join(folder, "public"), rest)
  if (!file) return page(404, "Not found", "This applet has no such page.")
  const f = Bun.file(file)
  return new Response(method === "HEAD" ? null : f, {
    headers: { "content-type": f.type || "application/octet-stream", "cache-control": "no-cache" },
  })
}

/** Resolves once something accepts on [port], or after [ms]: a server that was just (re)started gets a moment. */
export function waitForPort(port: number, ms: number): Promise<boolean> {
  const until = Date.now() + ms
  return new Promise((resolve) => {
    const attempt = () => {
      const s = connect({ port, host: "127.0.0.1" })
      s.once("connect", () => { s.destroy(); resolve(true) })
      s.once("error", () => {
        s.destroy()
        if (Date.now() >= until) resolve(false)
        else setTimeout(attempt, 150)
      })
    }
    attempt()
  })
}

export interface AppletRouteDeps {
  applets: AppletService
  /** The caller, from the request's credentials; undefined = not a paired device (or agent). */
  caller: (req: Request) => AppletCaller | undefined
  own: OwnCredentials
  /** How long a just-started server may take to listen. */
  startGraceMs?: number
}

export async function handleAppletRequest(req: Request, deps: AppletRouteDeps): Promise<Response> {
  const url = new URL(req.url)
  const m = matchAppletPath(url.pathname)
  if (!m) return page(404, "Not found", "No such applet.")
  if (!isAppletName(m.name)) return page(404, "Not found", "No such applet.")
  if (m.rest === null) return new Response(null, { status: 301, headers: { location: `/a/${m.name}/${url.search}` } })

  const caller = deps.caller(req)
  if (!caller) return page(401, "Sign-in expired", "Open this applet from supermux on a paired device.")

  const v = deps.applets.get(m.name)
  if (!v) return page(404, "Not found", `There is no applet named “${esc(m.name)}” on this host.`)
  if (!v.manifest) return page(503, "This applet can’t open", `${esc(v.error ?? "Its applet.json can’t be read.")} Ask an agent to fix it.`)
  if (caller.kind !== "device" && isOwnerOnly(m.rest, v.manifest.ownerOnly)) {
    return page(403, "Only the owner can open this", "This part of the applet is reserved for your own devices.")
  }

  if (!v.manifest.run) return serveStaticApplet(v.folder, m.rest, req.method.toUpperCase())

  let port = deps.applets.supervisor.port(v.row.id)
  const st = deps.applets.supervisor.state(v.row.id)
  if (!port || !st) {
    const why = st?.error ?? "Its server isn’t running."
    return page(503, "This applet isn’t running", `${esc(why)} Ask an agent to run <code>supermux applet logs ${esc(m.name)}</code>.`)
  }
  const grace = deps.startGraceMs ?? 10_000
  const startedAgo = st.startedAt ? Date.now() - Date.parse(st.startedAt) : Infinity
  if (startedAgo < grace) await waitForPort(port, grace - startedAgo)
  port = deps.applets.supervisor.port(v.row.id) ?? port

  const via = viaValue(caller)
  return handleProxyRequest(
    req,
    { port, sessionName: `applet ${m.name}` },
    { prefix: `/a/${m.name}`, upstreamPath: m.rest + url.search },
    deps.own,
    {
      timeoutMs: null,
      stripRequestPrefixes: ["x-mux-"],
      setHeaders: {
        "x-mux-via": via,
        "x-mux-via-sig": deps.applets.signVia(v.row.id, via),
        ...(caller.kind === "device" ? { "x-mux-device-name": encodeURIComponent(caller.name) } : {}),
        "x-forwarded-prefix": `/a/${m.name}`,
      },
      label: `The applet "${m.name}"`,
    },
  )
}
