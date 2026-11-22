// #163: what may notify a person, kept per account on the broker (the same on every device), and the ONE decision every
// notification goes through: a chat's reply, a question or permission prompt, an applet's supermux.notify, supermux's
// own notices, and the assistant overlay's bubble on the clients.
import type { Database as Db } from "bun:sqlite"

/** A group's or an item's level. "needs": only what blocks an agent (a question, a permission prompt) or a needsYou notice. */
export type Level = "all" | "needs" | "off"
export type AppletLevel = "on" | "off"
export type TargetKind = "session" | "applet"

export interface NotificationPrefs {
  agents: Level
  sessions: Level
  applets: AppletLevel
  /** Questions and permission prompts (and needsYou applet notices) notify even where the level is Off. */
  needsYou: boolean
  /** supermux's own notices: a worktree finished, the memory tidy-up. */
  notices: boolean
  /** The one-time "Sessions: Needs me" offer, for an account from before #163. */
  showTip: boolean
  /** Items with their own level: sessions (agents included) by session id, applets by applet id. */
  overrides: { session: Record<string, Level>; applet: Record<string, AppletLevel> }
  updatedAt?: string
  /** Who changed it last, as JSON ({"kind":"device","name":…}). */
  updatedBy?: string
}

/** A new account: every agent, sessions only when they need you, every applet. */
export const NEW_ACCOUNT: Omit<NotificationPrefs, "overrides"> = { agents: "all", sessions: "needs", applets: "on", needsYou: true, notices: true, showTip: false }

const LEVELS: readonly string[] = ["all", "needs", "off"]
const APPLET_LEVELS: readonly string[] = ["on", "off"]
export const isLevel = (v: unknown): v is Level => typeof v === "string" && LEVELS.includes(v)
export const isAppletLevel = (v: unknown): v is AppletLevel => typeof v === "string" && APPLET_LEVELS.includes(v)

/** Where a notification comes from. An agent is a session whose role is personal_assistant. */
export type PushSource =
  | { kind: "session"; id: string; agent: boolean }
  | { kind: "applet"; id: string }
  | { kind: "supermux" }

/** What happened: a reply, something that blocks an agent until answered, an applet notice, a supermux notice. */
export type PushTrigger = "reply" | "request" | "applet" | "notice"

export interface PushVerdict {
  send: boolean
  /** Which setting decided, for the push_decision log line: group | override | breakthrough | notices. */
  rule: "group" | "override" | "breakthrough" | "notices"
  level?: Level | AppletLevel
}

/**
 * The one decision. Presence ("that chat is open on a device") and internal workers are checked by the caller first;
 * this is only the person's settings. [needsYou]: an applet notice that asks for an answer (supermux.notify's option).
 */
export function decidePush(prefs: NotificationPrefs, source: PushSource, trigger: PushTrigger, needsYou = false): PushVerdict {
  if (source.kind === "supermux") return { send: prefs.notices, rule: "notices" }
  const needs = trigger === "request" || needsYou
  const own = source.kind === "session" ? prefs.overrides.session[source.id] : prefs.overrides.applet[source.id]
  const level: Level | AppletLevel = own ?? (source.kind === "applet" ? prefs.applets : source.agent ? prefs.agents : prefs.sessions)
  const rule = own ? "override" : "group"
  if (level === "all" || level === "on") return { send: true, rule, level }
  if (level === "needs" && needs) return { send: true, rule, level }
  if (level === "off" && needs && prefs.needsYou) return { send: true, rule: "breakthrough", level }
  return { send: false, rule, level }
}

/** A change from a device: any subset of the group fields, and items set (a level) or cleared (null = use the default). */
export interface NotificationPrefsPatch {
  agents?: Level
  sessions?: Level
  applets?: AppletLevel
  needsYou?: boolean
  notices?: boolean
  showTip?: boolean
  session?: Record<string, Level | null>
  applet?: Record<string, AppletLevel | null>
}

/** Checks a PATCH body; a message for the first field it can't take. */
export function parsePatch(body: unknown): { ok: true; patch: NotificationPrefsPatch } | { ok: false; error: string } {
  if (!body || typeof body !== "object") return { ok: false, error: "a JSON object is required" }
  const b = body as Record<string, unknown>
  const patch: NotificationPrefsPatch = {}
  for (const k of ["agents", "sessions"] as const) {
    if (b[k] === undefined) continue
    if (!isLevel(b[k])) return { ok: false, error: `${k} must be all, needs or off` }
    patch[k] = b[k] as Level
  }
  if (b.applets !== undefined) {
    if (!isAppletLevel(b.applets)) return { ok: false, error: "applets must be on or off" }
    patch.applets = b.applets
  }
  for (const k of ["needsYou", "notices", "showTip"] as const) {
    if (b[k] === undefined) continue
    if (typeof b[k] !== "boolean") return { ok: false, error: `${k} must be true or false` }
    patch[k] = b[k] as boolean
  }
  for (const [k, ok, words] of [["session", isLevel, "all, needs, off or null"], ["applet", isAppletLevel, "on, off or null"]] as const) {
    const items = b[k]
    if (items === undefined) continue
    if (!items || typeof items !== "object" || Array.isArray(items)) return { ok: false, error: `${k} must map ids to a level` }
    const out: Record<string, any> = {}
    for (const [id, v] of Object.entries(items as Record<string, unknown>)) {
      if (!id) return { ok: false, error: `${k} has an empty id` }
      if (v !== null && !ok(v)) return { ok: false, error: `${k}.${id} must be ${words}` }
      out[id] = v
    }
    patch[k] = out
  }
  return { ok: true, patch }
}

type PrefsRow = { agents: string; sessions: string; applets: string; needs_you: number; notices: number; show_tip: number; updated_at: string; updated_by: string | null }
type OverrideRow = { target_kind: string; target_id: string; level: string }

/** The table pair from migration 055, read and written per user. Write-through, no cache: a handful of rows. */
export class NotificationPrefsStore {
  constructor(private readonly db: Db) {}

  get(user: string): NotificationPrefs {
    const row = this.db.query("SELECT agents, sessions, applets, needs_you, notices, show_tip, updated_at, updated_by FROM notification_prefs WHERE user_id = ?").get(user) as PrefsRow | null
    const overrides: NotificationPrefs["overrides"] = { session: {}, applet: {} }
    for (const o of this.db.query("SELECT target_kind, target_id, level FROM notification_overrides WHERE user_id = ?").all(user) as OverrideRow[]) {
      if (o.target_kind === "session" && isLevel(o.level)) overrides.session[o.target_id] = o.level
      else if (o.target_kind === "applet" && isAppletLevel(o.level)) overrides.applet[o.target_id] = o.level
    }
    if (!row) return { ...NEW_ACCOUNT, overrides }
    return {
      agents: isLevel(row.agents) ? row.agents : NEW_ACCOUNT.agents,
      sessions: isLevel(row.sessions) ? row.sessions : NEW_ACCOUNT.sessions,
      applets: isAppletLevel(row.applets) ? row.applets : NEW_ACCOUNT.applets,
      needsYou: !!row.needs_you,
      notices: !!row.notices,
      showTip: !!row.show_tip,
      overrides,
      updatedAt: row.updated_at,
      ...(row.updated_by ? { updatedBy: row.updated_by } : {}),
    }
  }

  /** Applies [patch] for [user] as [by] (the actor's JSON) and returns the result. */
  apply(user: string, patch: NotificationPrefsPatch, by?: string): NotificationPrefs {
    const now = new Date().toISOString()
    const cur = this.get(user)
    const next = {
      agents: patch.agents ?? cur.agents, sessions: patch.sessions ?? cur.sessions, applets: patch.applets ?? cur.applets,
      needsYou: patch.needsYou ?? cur.needsYou, notices: patch.notices ?? cur.notices, showTip: patch.showTip ?? cur.showTip,
    }
    const tx = this.db.transaction(() => {
      if (patch.agents || patch.sessions || patch.applets || patch.needsYou !== undefined || patch.notices !== undefined || patch.showTip !== undefined) {
        this.db.run(
          `INSERT INTO notification_prefs (user_id, agents, sessions, applets, needs_you, notices, show_tip, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (user_id) DO UPDATE SET agents = excluded.agents, sessions = excluded.sessions, applets = excluded.applets, needs_you = excluded.needs_you,
             notices = excluded.notices, show_tip = excluded.show_tip, updated_at = excluded.updated_at, updated_by = excluded.updated_by`,
          [user, next.agents, next.sessions, next.applets, next.needsYou ? 1 : 0, next.notices ? 1 : 0, next.showTip ? 1 : 0, now, by ?? null],
        )
      }
      for (const kind of ["session", "applet"] as const) {
        for (const [id, level] of Object.entries(patch[kind] ?? {})) this.setItemRow(user, kind, id, level, now, by)
      }
    })
    tx()
    return this.get(user)
  }

  /** One item's own level, or null to follow its group again. */
  setItem(user: string, kind: TargetKind, id: string, level: Level | AppletLevel | null, by?: string): NotificationPrefs {
    return this.apply(user, { [kind]: { [id]: level } } as NotificationPrefsPatch, by)
  }

  /** Forgets an item that no longer exists (a session removed for good, an applet deleted). */
  forget(kind: TargetKind, id: string): void {
    this.db.run("DELETE FROM notification_overrides WHERE target_kind = ? AND target_id = ?", [kind, id])
  }

  private setItemRow(user: string, kind: TargetKind, id: string, level: string | null, now: string, by?: string): void {
    if (level === null) {
      this.db.run("DELETE FROM notification_overrides WHERE user_id = ? AND target_kind = ? AND target_id = ?", [user, kind, id])
      return
    }
    this.db.run(
      `INSERT INTO notification_overrides (user_id, target_kind, target_id, level, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (user_id, target_kind, target_id) DO UPDATE SET level = excluded.level, updated_at = excluded.updated_at, updated_by = excluded.updated_by`,
      [user, kind, id, level, now, by ?? null],
    )
  }
}
