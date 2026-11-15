import { readFileSync } from "fs"
import { Resvg } from "@resvg/resvg-js"
import { appletPayload, type AppletService, type AppletView } from "../../core/applets/service"
import { resolveStaticFile } from "./applet-route"

/**
 * #117: what a paired device (the owner) does with applets: the Applets grid, Applet info, Run now, secrets, settings,
 * backups and delete. Agents never come through here: they use `supermux applet …`, which refuses delete and secret
 * values. Paths:
 *   GET    /applets                         the list
 *   GET    /applets/.deleted                deleted applets still kept (30 days)
 *   POST   /applets/.deleted/<id>/undelete
 *   GET    /applets/<name>                  everything Applet info shows
 *   PATCH  /applets/<name> {title}            Settings ▸ Name (written to applet.json; the link stays)
 *   DELETE /applets/<name>
 *   GET    /applets/<name>/icon | logs[?lines=] | jobs/<job>/runs
 *   POST   /applets/<name>/restart | backup {why} | jobs/<job>/run | pin | unpin | stop | start | notify {title, body} | ask {text, agent?}
 *   PUT    /applets/<name>/secrets/<NAME> {value, expiresAt?}   DELETE the same
 *   PUT    /applets/<name>/settings/<key> {value}
 */

type Json = (body: unknown, status?: number) => Response

function detail(applets: AppletService, v: AppletView) {
  const m = v.manifest
  const settingsValues = applets.secrets.settings(v.row.id)
  return {
    ...appletPayload(v, applets.needsYou(v), applets.pinnedIds().includes(v.row.id) ? applets.pinnedIds().indexOf(v.row.id) : null),
    secrets: applets.secrets.status(v.row.id, m?.secrets ?? []).map((s) => ({ ...s, wanted: (m?.secrets ?? []).includes(s.name) })),
    jobs: (m?.jobs ?? []).map((j) => {
      const last = applets.jobs.lastRun(v.row.id, j.name)
      return {
        name: j.name, every: j.every, running: applets.jobs.isRunning(v.row.id, j.name),
        lastRun: last ? { startedAt: last.startedAt, finishedAt: last.finishedAt, exitCode: last.exitCode, trigger: last.trigger } : null,
      }
    }),
    settings: (m?.config ?? []).map((f) => ({ ...f, value: settingsValues[f.key] ?? (f.default !== undefined ? String(f.default) : null) })),
    backups: applets.backups.list(v.row.name),
    data: applets.dataInfo(v),
    run: m?.run ?? null,
    folder: v.folder,
  }
}

async function body(req: Request): Promise<Record<string, unknown>> {
  const b = await req.json().catch(() => ({}))
  return b && typeof b === "object" && !Array.isArray(b) ? b as Record<string, unknown> : {}
}

export async function handleAppletApi(req: Request, opts: { applets: AppletService; device: string; json: Json }): Promise<Response> {
  const { applets, json } = opts
  const by = `device:${opts.device}`
  const url = new URL(req.url)
  const method = req.method.toUpperCase()
  const parts = url.pathname.split("/").slice(2) // platform-ok: URL path segments
    .map((p) => { try { return decodeURIComponent(p) } catch { return "\u0000" } })

  if (parts.length === 0 || (parts.length === 1 && parts[0] === "")) {
    if (method === "GET") {
      const pins = applets.pinnedIds()
      return json(applets.list().map((v) => appletPayload(v, applets.needsYou(v), pins.includes(v.row.id) ? pins.indexOf(v.row.id) : null)))
    }
    return json({ error: "not found" }, 404)
  }

  if (parts[0] === ".deleted") {
    if (method === "GET" && parts.length === 1) {
      return json(applets.store.listDeleted().map((r) => ({ id: r.id, name: r.name, title: r.title, deletedAt: r.deletedAt, deletedBy: r.updatedBy })))
    }
    if (method === "POST" && parts.length === 3 && parts[2] === "undelete") {
      const r = await applets.undelete(parts[1]!, by)
      return r.ok ? json(r) : json({ error: r.error }, 409)
    }
    return json({ error: "not found" }, 404)
  }

  const v = applets.get(parts[0]!)
  if (!v) return json({ error: "no such applet" }, 404)
  const name = v.row.name
  const [, a, b, c] = parts

  if (!a) {
    if (method === "GET") return json(detail(applets, v))
    if (method === "PATCH") {
      const p = await body(req)
      if (typeof p.title !== "string") return json({ error: "title is required" }, 400)
      const r = await applets.setTitle(name, p.title, by)
      return r.ok ? json({ ok: true }) : json({ error: r.error }, 400)
    }
    if (method === "DELETE") {
      const r = await applets.delete(name, by)
      return r.ok ? json(r) : json({ error: r.error }, 409)
    }
  }
  if (a === "icon" && !b && method === "GET") {
    const file = v.row.icon ? resolveStaticFile(v.folder, v.row.icon) : null
    if (!file) return new Response("not found", { status: 404 })
    // An SVG icon goes out as a 256 px PNG: every app draws a PNG, not every app has an SVG decoder.
    if (/\.svg$/i.test(file)) {
      try {
        const png = new Resvg(readFileSync(file, "utf8"), { fitTo: { mode: "width", value: 256 } }).render().asPng()
        return new Response(png, { headers: { "content-type": "image/png", "cache-control": "no-cache" } })
      } catch { /* not a drawable SVG: sent as it is */ }
    }
    const f = Bun.file(file)
    return new Response(f, { headers: { "content-type": f.type || "application/octet-stream", "cache-control": "no-cache" } })
  }
  if (a === "logs" && !b && method === "GET") {
    const lines = Math.min(2000, Math.max(1, Number(url.searchParams.get("lines")) || 200))
    return json({ logs: applets.supervisor.logs(v.row.id, lines) })
  }
  if (a === "ask" && !b && method === "POST") {
    const p = await body(req)
    const r = await applets.ask(name, p.text, p.agent ?? undefined)
    return r.ok ? json({ ok: true, sessionId: r.sessionId, session: r.session }) : json({ error: r.error }, 400)
  }
  if (a === "notify" && !b && method === "POST") {
    const p = await body(req)
    const r = await applets.notify(name, p.title, p.body)
    return r.ok ? json({ ok: true }) : json({ error: r.error }, 400)
  }
  if ((a === "stop" || a === "start") && !b && method === "POST") {
    const r = await applets.setStopped(name, a === "stop", by)
    return r.ok ? json({ ok: true }) : json({ error: r.error }, 409)
  }
  if ((a === "pin" || a === "unpin") && !b && method === "POST") {
    const r = a === "pin" ? applets.pin(name) : applets.unpin(name)
    return r.ok ? json({ ok: true, pins: applets.pinnedIds() }) : json({ error: r.error }, 409)
  }
  if (a === "restart" && !b && method === "POST") {
    const r = await applets.restart(name)
    return r.ok ? json(r) : json({ error: r.error }, 409)
  }
  if (a === "backup" && !b && method === "POST") {
    const why = (await body(req)).why
    const r = applets.backup(name, typeof why === "string" ? why : undefined)
    return r.ok ? json(r) : json({ error: r.error }, 409)
  }
  if (a === "jobs" && b && c === "runs" && method === "GET") {
    return json(applets.jobs.runs(v.row.id, b, Math.min(100, Number(url.searchParams.get("limit")) || 20)))
  }
  if (a === "jobs" && b && c === "run" && method === "POST") {
    const r = await applets.runJob(name, b)
    return r.ok ? json(r.run) : json({ error: r.error }, 404)
  }
  if (a === "secrets" && b && !c) {
    if (method === "PUT") {
      const p = await body(req)
      if (typeof p.value !== "string") return json({ error: "value is required" }, 400)
      const expiresAt = typeof p.expiresAt === "string" ? p.expiresAt : null
      const r = await applets.setSecret(name, b, p.value, { expiresAt, by })
      return r.ok ? json({ ok: true }) : json({ error: r.error }, 400)
    }
    if (method === "DELETE") {
      const r = await applets.removeSecret(name, b)
      return r.ok ? json({ ok: true }) : json({ error: r.error }, 404)
    }
  }
  if (a === "settings" && b && !c && method === "PUT") {
    const p = await body(req)
    if (typeof p.value !== "string") return json({ error: "value is required" }, 400)
    const r = await applets.setSetting(name, b, p.value, by)
    return r.ok ? json({ ok: true }) : json({ error: r.error }, 400)
  }
  return json({ error: "not found" }, 404)
}
