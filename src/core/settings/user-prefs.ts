// #152 B4: a person's own choices, kept on the broker per user (never per device) and per project folder ('' =
// everywhere): the launcher's Local / Worktree and the new-branch prefix. "owner" is the only user until users exist.
import type { Database as Db } from "bun:sqlite"
import { gitAsync } from "../git/exec"

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

/** The first name git commits with in [dir], as a branch prefix ("Ahmet Hüseyin Dok" → "ahmet/"); else mux/. */
export async function defaultBranchPrefix(dir: string): Promise<string> {
  const name = await gitAsync(dir, ["config", "user.name"], { timeoutMs: 5_000 }).catch(() => "")
  const first = name.trim().split(/\s+/)[0] ?? ""
  const slug = first.replace(/ı/g, "i").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9-]+/g, "")
  return slug ? `${slug}/` : "mux/"
}

/** What the launcher starts with in [dir]: the last Local / Worktree pick there (Worktree for a new project), and the prefix. */
export async function launcherPrefs(prefs: UserPrefs, user: string, dir: string): Promise<LauncherPrefs> {
  const workIn = prefs.get(user, dir, "workIn") === "local" ? "local" : "worktree"
  return { workIn, branchPrefix: prefs.get(user, "", "branchPrefix") ?? await defaultBranchPrefix(dir) }
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
