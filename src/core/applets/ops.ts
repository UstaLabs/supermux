import { isAbsolute, join } from "path"
import { mkdirSync, writeFileSync } from "fs"
import previewTemplate from "./widget-preview.tmpl" with { type: "text" }
import type { AppletService, AppletView } from "./service"

/**
 * #117: `supermux applet …` on the broker's side. The CLI (#156) sends these ops over the calling session's own
 * socket, so the broker knows which session asks (recorded as agent:<session id>). Open to every session. Deleting an
 * applet and secret values are the owner's, on a paired device: refused here whatever the session.
 */

export type AppletOpResult = { ok: true; value: unknown } | { ok: false; error: string; code?: "not_found" | "denied" | "usage" }

export interface AppletOpContext {
  sessionId: string
  /** The broker's public URL, for links; undefined = relative links. */
  publicUrl?: string
}

const OWNER_ONLY = new Set(["applet_delete", "applet_secret", "applet_secrets", "applet_set_secret", "applet_share"])

export function isAppletOp(name: string): boolean {
  return name.startsWith("applet_")
}

const link = (ctx: AppletOpContext, name: string) => `${(ctx.publicUrl ?? "").replace(/\/+$/, "")}/a/${name}/`

function str(args: Record<string, unknown> | undefined, k: string): string | undefined {
  const v = args?.[k]
  return typeof v === "string" && v.trim() ? v.trim() : undefined
}

function stateOf(v: AppletView): string {
  if (v.row.status === "missing") return "missing"
  if (v.row.status === "invalid") return "invalid"
  if (!v.manifest?.run) return "static"
  return v.process?.state ?? "stopped"
}

export async function handleAppletOp(svc: AppletService, name: string, args: Record<string, unknown> | undefined, ctx: AppletOpContext): Promise<AppletOpResult> {
  const by = `agent:${ctx.sessionId}`
  if (OWNER_ONLY.has(name)) {
    return { ok: false, code: "denied", error: "not allowed: only the owner can delete an applet or see or set secret values, on a paired device (Applet info). Ask them." }
  }
  const need = (): string | AppletOpResult => str(args, "applet") ?? { ok: false, code: "usage", error: "which applet? (supermux applet list)" }
  const find = (n: string): AppletView | AppletOpResult => svc.get(n) ?? { ok: false, code: "not_found", error: `not found: no applet named "${n}" (supermux applet list)` }

  switch (name) {
    case "applet_list": {
      await svc.scan()
      return {
        ok: true,
        value: svc.list().map((v) => ({
          name: v.row.name, title: v.row.title, state: stateOf(v), url: link(ctx, v.row.name),
          needs_you: svc.needsYou(v).map((s) => `${s.name} ${s.state}`), error: v.error ?? v.process?.error ?? null,
          updated_by: v.row.updatedBy, updated_at: v.row.updatedAt,
        })),
      }
    }
    case "applet_info": {
      const n = need(); if (typeof n !== "string") return n
      await svc.scan()
      const v = find(n); if (!("row" in v)) return v
      const m = v.manifest
      return {
        ok: true,
        value: {
          name: v.row.name, title: v.row.title, id: v.row.id, state: stateOf(v), url: link(ctx, v.row.name),
          folder: v.folder, data_dir: svc.dataDir(v.folder, m), error: v.error ?? v.process?.error ?? null,
          manifest: m ?? null,
          server: m?.run ? { run: m.run, state: v.process?.state ?? "stopped", restarts: v.process?.restarts ?? 0, last_exit: v.process?.lastExit ?? null } : null,
          jobs: (m?.jobs ?? []).map((j) => {
            const last = svc.jobs.lastRun(v.row.id, j.name)
            return { name: j.name, every: j.every, run: j.run, last_run: last ? { started_at: last.startedAt, exit_code: last.exitCode, trigger: last.trigger } : null }
          }),
          secrets: svc.secrets.status(v.row.id, m?.secrets ?? []).filter((s) => (m?.secrets ?? []).includes(s.name)).map((s) => ({ name: s.name, state: s.state })),
          backups: svc.backups.list(v.row.name).map((b) => ({ path: join(svc.backups.dirOf(v.row.name), b.id, "data"), kind: b.kind, created_at: b.createdAt, why: b.why })),
          bin: m?.bin ?? [],
          updated_by: v.row.updatedBy, updated_at: v.row.updatedAt,
        },
      }
    }
    case "applet_new": {
      const n = need(); if (typeof n !== "string") return n
      const from = str(args, "from")
      if (from && !isAbsolute(from)) return { ok: false, code: "usage", error: "--from must be an absolute path" }
      const r = await svc.create(n, { ...(from ? { from } : {}), ...(str(args, "title") ? { title: str(args, "title") } : {}) }, by)
      if (!r.ok) return { ok: false, error: r.error }
      const v = r.view
      return { ok: true, value: { name: v.row.name, title: v.row.title, folder: v.folder, url: link(ctx, v.row.name), state: stateOf(v), error: v.error ?? null, warnings: r.warnings } }
    }
    case "applet_restart": {
      const n = need(); if (typeof n !== "string") return n
      if (!("row" in find(n))) { await svc.scan(); const again = find(n); if (!("row" in again)) return again }
      const r = await svc.restart(n)
      if (!r.ok) return { ok: false, error: r.error }
      const v = svc.get(n)!
      if (!r.server) return { ok: true, value: { name: n, server: false, state: "static", note: "static applet: nothing to restart (public/ is served as it is)" } }
      // Give it a moment, so a server that dies on start says so here instead of looking fine.
      for (let i = 0; i < 20; i++) {
        await Bun.sleep(100)
        if (svc.supervisor.state(v.row.id)?.state !== "running") break
      }
      svc.touchBy(v.row.id, by)
      const st = svc.supervisor.state(v.row.id)
      return {
        ok: true,
        value: {
          name: n, server: true, state: st?.state ?? "stopped", error: st?.error ?? null,
          logs: svc.supervisor.logsSinceStart(v.row.id, 30),
          note: st?.state === "running"
            ? `restarted; the server keeps running after this command returned. Check it with: supermux applet logs ${n}`
            : `the server did not stay up; fix it and run supermux applet restart ${n} again`,
        },
      }
    }
    case "applet_logs": {
      const n = need(); if (typeof n !== "string") return n
      const v = find(n); if (!("row" in v)) return v
      const lines = typeof args?.lines === "number" ? Math.min(2000, Math.max(1, args.lines)) : 100
      const job = str(args, "job")
      if (job) {
        if (!v.manifest?.jobs.some((j) => j.name === job)) return { ok: false, code: "not_found", error: `not found: "${n}" has no job "${job}" (its jobs: ${v.manifest?.jobs.map((j) => j.name).join(", ") || "none"})` }
        const runs = svc.jobs.runs(v.row.id, job, 5)
        return { ok: true, value: { name: n, job, runs: runs.map((r) => ({ started_at: r.startedAt, finished_at: r.finishedAt, exit_code: r.exitCode, trigger: r.trigger, output: r.output.split("\n").slice(-lines).join("\n") })) } }
      }
      if (!v.manifest?.run) return { ok: true, value: { name: n, logs: "", note: "static applet: it has no server, so no server output (jobs: --job NAME)" } }
      return { ok: true, value: { name: n, state: v.process?.state ?? "stopped", logs: svc.supervisor.logs(v.row.id, lines) } }
    }
    case "applet_run": {
      const n = need(); if (typeof n !== "string") return n
      const job = str(args, "job")
      if (!job) return { ok: false, code: "usage", error: "which job? (supermux applet info " + n + ")" }
      const r = await svc.runJob(n, job)
      if (!r.ok) return { ok: false, code: "not_found", error: r.error.startsWith("not found") ? r.error : `not found: ${r.error}` }
      return { ok: true, value: { name: n, job, exit_code: r.run.exitCode, output: r.run.output, started_at: r.run.startedAt, finished_at: r.run.finishedAt } }
    }
    case "applet_backup": {
      const n = need(); if (typeof n !== "string") return n
      const r = svc.backup(n, str(args, "why"))
      if (!r.ok) return { ok: false, error: r.error }
      return { ok: true, value: { name: n, path: join(svc.backups.dirOf(n), r.backup.id, "data"), why: r.backup.why, created_at: r.backup.createdAt } }
    }
    case "applet_widget": {
      // #165: supermux applet widget show|check|changed|run APPLET …
      const n = need(); if (typeof n !== "string") return n
      const v = find(n); if (!("row" in v)) return v
      const what = str(args, "do") ?? "check"
      if (what === "changed") {
        const r = await svc.refreshWidget(n)
        if (!r.ok) return { ok: false, error: r.error }
        return { ok: true, value: { name: n, summary: r.summary, problems: r.problems, note: svc.widget(v.row.id) ? "phones redraw it now (Android at once; iPhone on its next reload)" : "no widget was read" } }
      }
      if (what === "run") {
        const action = str(args, "action")
        if (!action) return { ok: false, code: "usage", error: `which action? (${Object.keys(v.manifest?.widget?.actions ?? {}).join(", ") || "applet.json has none"})` }
        const r = await svc.runWidgetAction(n, action, args?.args ?? {}, by)
        if (!r.ok) return { ok: false, error: r.error }
        return { ok: true, value: { name: n, action, widget: r.widget } }
      }
      const c = await svc.checkWidget(n)
      if (!c.ok) return { ok: false, error: c.error }
      if (what === "check") return { ok: true, value: { name: n, problems: c.problems, sizes: c.doc ? Object.keys(c.doc.layouts) : [] } }
      if (what !== "show") return { ok: false, code: "usage", error: `unknown: ${what} (show, check, changed, run)` }
      if (!c.doc) return { ok: false, error: c.problems.join("\n") }
      const cfg = v.manifest!.widget!
      const port = svc.supervisor.port(v.row.id)
      const base = v.manifest?.run ? (port ? `http://127.0.0.1:${port}/` : "") : `file://${join(v.folder, "public")}/`
      const data = {
        title: v.row.title, accent: cfg.accent ?? "#4b6bea", doc: c.doc, problems: c.problems, base,
        html: cfg.html && base ? base + cfg.html.replace(/^\//, "") : null,
        icon: v.manifest?.icon ? `file://${join(v.folder, v.manifest.icon)}` : null,
      }
      mkdirSync(svc.previewDir(), { recursive: true })
      const file = join(svc.previewDir(), `${n}.html`)
      writeFileSync(file, previewTemplate.replace("/*__DATA__*/null", () => JSON.stringify(data).replace(/</g, "\\u003c")))
      return { ok: true, value: { name: n, file, problems: c.problems, note: "open it in a browser, or screenshot it, to see every size in light and dark as the phones draw them" } }
    }
    default:
      return { ok: false, code: "usage", error: `unknown applet op ${name}` }
  }
}
