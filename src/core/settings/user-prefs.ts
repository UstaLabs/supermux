// #152 B4: a person's own choices, kept on the broker per user (never per device) and per project folder ('' =
// everywhere): the launcher's Local / Worktree and the new-branch prefix. "owner" is the only user until users exist.
import type { Database as Db } from "bun:sqlite"
import { gitAsync } from "../git/exec"
import { hostOf } from "../git/forge"

export const OWNER = "owner"

export class UserPrefs {
  constructor(private readonly db: Db) {}

  get(user: string, scope: string, key: string): string | undefined {
    const row = this.db.query("SELECT value FROM user_prefs WHERE user_id = ? AND scope = ? AND key = ?").get(user, scope, key) as { value: string } | null
    return row?.value
  }

  set(user: string, scope: string, key: string, value: string): void {
    this.db.run(
      "INSERT INTO user_prefs (user_id, scope, key, value, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, scope, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
      [user, scope, key, value, new Date().toISOString()],
    )
  }
}

export type WorkIn = "local" | "worktree"
export interface LauncherPrefs { workIn: WorkIn; branchPrefix: string }

/** The forge logins connected for a host ("github.com" → ["octocat"]). */
export type ForgeLogins = (host: string) => string[]

/** A name as a branch prefix: ASCII, lowercase ("Çağrı" → "cagri/"), or "" when nothing is left. */
function prefixOf(name: string): string {
  const slug = name.replace(/ı/g, "i").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9-]+/g, "")
  return slug ? `${slug}/` : ""
}

/**
 * The prefix a user who never set one gets: the login of the one forge account connected for the project's origin
 * (what people see on pull requests), else the first name git commits with ("Ahmet Hüseyin Dok" → "ahmet/"), else mux/.
 */
export async function defaultBranchPrefix(dir: string, forgeLogins: ForgeLogins = () => []): Promise<string> {
  const origin = await gitAsync(dir, ["remote", "get-url", "origin"], { timeoutMs: 5_000 }).catch(() => "")
  const host = origin ? hostOf(origin) : null
  const logins = host ? forgeLogins(host) : []
  if (logins.length === 1 && prefixOf(logins[0]!)) return prefixOf(logins[0]!)
  const name = await gitAsync(dir, ["config", "user.name"], { timeoutMs: 5_000 }).catch(() => "")
  const first = name.trim().split(/\s+/)[0] ?? ""
  return prefixOf(first) || "mux/"
}

/** What the launcher starts with in [dir]: the last Local / Worktree pick there (Worktree for a new project), and the prefix. */
export async function launcherPrefs(prefs: UserPrefs, user: string, dir: string, forgeLogins?: ForgeLogins): Promise<LauncherPrefs> {
  const workIn = prefs.get(user, dir, "workIn") === "local" ? "local" : "worktree"
  return { workIn, branchPrefix: prefs.get(user, "", "branchPrefix") ?? await defaultBranchPrefix(dir, forgeLogins) }
}

/** Remembers a pick: Local / Worktree for [dir], the prefix for every project. Throws on a value it can't keep. */
export async function setLauncherPrefs(prefs: UserPrefs, user: string, dir: string, patch: Partial<LauncherPrefs>): Promise<void> {
  if (patch.workIn !== undefined && patch.workIn !== "local" && patch.workIn !== "worktree") throw new Error("workIn must be local or worktree")
  const prefix = patch.branchPrefix
  if (prefix && (prefix.startsWith("-") || !(await refFormatOk(dir, `${prefix}x`)))) throw new Error(`not a branch prefix git accepts: ${prefix}`)
  if (patch.workIn !== undefined) prefs.set(user, dir, "workIn", patch.workIn)
  if (prefix !== undefined) prefs.set(user, "", "branchPrefix", prefix)
}

/** Whether git takes [name] as a branch name. */
export async function refFormatOk(dir: string, name: string): Promise<boolean> {
  return gitAsync(dir, ["check-ref-format", "--branch", name], { timeoutMs: 5_000 }).then(() => true, () => false)
}
