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

/**
 * The page API an applet includes with `<script src="_supermux/api.js"></script>` (relative to its page). It defines
 * `window.supermux`:
 *   notify(title, body, {needsYou})  a push to his devices; plain fetch, so it works in any browser on a paired device.
 *                            needsYou: true marks a notice that waits on him (it gets through "Needs me", #163)
 *   agent.ask(text, {agent}) a message to an agent about this applet; only in supermux's applet window, after a click
 *   save(blob | url, name)   saves a file through supermux's downloads; only in supermux's applet window
 *   openChat(sessionId)      shows a chat agent.ask answered with (its "Sent to …" link); supermux's window only
 * Each returns a promise of {ok: true, …} or {ok: false, reason}.
 */
export const SUPERMUX_API_JS = `(function () {
  if (window.supermux) return;
  var bridge = window.hostBridge;
  function fail(reason) { return Promise.resolve({ ok: false, reason: reason }); }
  function answer(r) { return r && r.ok === true ? r : { ok: false, reason: String((r && r.reason) || 'refused') }; }
  function err(e) { return { ok: false, reason: String((e && e.message) || e) }; }
  function notify(title, body, opts) {
    return fetch('_supermux/notify', { method: 'POST', headers: { 'content-type': 'application/json' }, credentials: 'same-origin',
      body: JSON.stringify({ title: String(title || ''), body: body == null ? '' : String(body), needsYou: !!(opts && opts.needsYou) }) })
      .then(function (r) { return r.json().then(function (j) { return r.ok ? { ok: true } : { ok: false, reason: String(j.error || r.status) }; }); }, err);
  }
  function ask(text, opts) {
    if (!bridge) return fail('only in the supermux app');
    return bridge.callAfterClick('ask', { text: String(text || ''), agent: opts && opts.agent ? String(opts.agent) : null }).then(answer, err);
  }
  function save(data, name) {
    if (!bridge) return fail('only in the supermux app');
    var send = function (url) { return bridge.callAfterClick('save', { url: url, name: String(name || 'download') }).then(answer, err); };
    if (typeof data === 'string') return send(data);
    return new Promise(function (resolve) {
      var r = new FileReader();
      r.onload = function () { resolve(send(String(r.result))); };
      r.onerror = function () { resolve({ ok: false, reason: 'could not read the file' }); };
      r.readAsDataURL(data);
    });
  }
  function openChat(sessionId) {
    if (!bridge) return fail('only in the supermux app');
    return bridge.call('openChat', { sessionId: String(sessionId || '') }).then(function (r) { return r === true ? { ok: true } : { ok: false, reason: 'no such chat' }; }, err);
  }
  window.supermux = { notify: notify, agent: { ask: ask }, save: save, openChat: openChat, inApp: !!bridge };
})();
`

async function supermuxApi(req: Request, rest: string, name: string, caller: AppletCaller, deps: AppletRouteDeps): Promise<Response> {
  const method = req.method.toUpperCase()
  if (rest === "/api.js" && (method === "GET" || method === "HEAD")) {
    return new Response(method === "HEAD" ? null : SUPERMUX_API_JS, { headers: { "content-type": "text/javascript; charset=utf-8", "cache-control": "no-cache" } })
  }
  if (rest === "/notify" && method === "POST") {
    if (caller.kind !== "device") return new Response(JSON.stringify({ error: "only the owner's devices can notify from a page" }), { status: 403, headers: { "content-type": "application/json" } })
    const b = await req.json().catch(() => ({})) as Record<string, unknown>
    const r = await deps.applets.notify(name, b.title, b.body, b.needsYou)
    return new Response(JSON.stringify(r.ok ? { ok: true } : { error: r.error }), { status: r.ok ? 200 : 400, headers: { "content-type": "application/json" } })
  }
  return new Response(JSON.stringify({ error: "no such supermux call" }), { status: 404, headers: { "content-type": "application/json" } })
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
  // supermux's own page API, for every applet: the script and what it calls without the native bridge.
  if (m.rest === "/_supermux" || m.rest.startsWith("/_supermux/")) return supermuxApi(req, m.rest.slice("/_supermux".length), v.row.name, caller, deps)
  if (caller.kind !== "device" && isOwnerOnly(m.rest, v.manifest.ownerOnly)) {
    return page(403, "Only the owner can open this", "This part of the applet is reserved for your own devices.")
  }

  if (v.row.stoppedAt) return page(503, "This applet is stopped", "Start it again from Applet info in supermux.")
  if (!v.manifest.run) {
    // A static applet's data API (a server applet owns its whole route, /api included).
    if (m.rest === "/api" || m.rest.startsWith("/api/")) return deps.applets.dataApi.handle(req, deps.applets.dataDir(v.folder, v.manifest), m.rest.slice(4))
    return serveStaticApplet(v.folder, m.rest, req.method.toUpperCase())
  }

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
