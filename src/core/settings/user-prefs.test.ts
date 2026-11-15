// #152 B4: the launcher's Local / Worktree choice per project and the new-branch prefix, kept on the broker per
// user (never per device).
import { expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { defaultBranchPrefix, launcherPrefs, setLauncherPrefs, UserPrefs } from "./user-prefs"

const store = () => { const db = openDb(":memory:"); runMigrations(db, MIGRATIONS); return new UserPrefs(db) }
function repo(userName?: string): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-prefs-"))
  execFileSync("git", ["init", "-q", "-b", "main", dir])
  // An empty name in the repo's own config hides the machine's, as a user with no git identity has none.
  execFileSync("git", ["-C", dir, "config", "user.name", userName ?? ""])
  return dir
}

test("a choice is the user's, per project folder; another user or folder doesn't see it", () => {
  const p = store()
  p.set("owner", "/a", "workIn", "local")
  expect(p.get("owner", "/a", "workIn")).toBe("local")
  expect(p.get("owner", "/b", "workIn")).toBeUndefined()
  expect(p.get("alice", "/a", "workIn")).toBeUndefined()
  p.set("owner", "/a", "workIn", "worktree")
  expect(p.get("owner", "/a", "workIn")).toBe("worktree")
})

test("a new project starts on Worktree, and the last choice is remembered", async () => {
  const p = store(), dir = repo("Ahmet Hüseyin Dok")
  expect((await launcherPrefs(p, "owner", dir)).workIn).toBe("worktree")
  await setLauncherPrefs(p, "owner", dir, { workIn: "local" })
  expect((await launcherPrefs(p, "owner", dir)).workIn).toBe("local")
})

test("the branch prefix: the user's own, else the first name git commits with, else mux/", async () => {
  expect(await defaultBranchPrefix(repo("Ahmet Hüseyin Dok"))).toBe("ahmet/")
  expect(await defaultBranchPrefix(repo("Işıl Çağlar"))).toBe("isil/")
  expect(await defaultBranchPrefix(repo())).toBe("mux/")
  const p = store(), dir = repo("Ahmet Hüseyin Dok")
  expect((await launcherPrefs(p, "owner", dir)).branchPrefix).toBe("ahmet/")
  await setLauncherPrefs(p, "owner", dir, { branchPrefix: "ahd/" })
  expect((await launcherPrefs(p, "owner", repo("Deniz"))).branchPrefix).toBe("ahd/")   // the user's, in every project
  await setLauncherPrefs(p, "owner", dir, { branchPrefix: "" })
  expect((await launcherPrefs(p, "owner", dir)).branchPrefix).toBe("")                // no prefix at all is a choice too
})

test("only Local or Worktree, and a prefix git accepts, are kept", async () => {
  const p = store(), dir = repo("A")
  await expect(setLauncherPrefs(p, "owner", dir, { workIn: "cloud" as any })).rejects.toThrow()
  await expect(setLauncherPrefs(p, "owner", dir, { branchPrefix: "a b/" })).rejects.toThrow()
  await expect(setLauncherPrefs(p, "owner", dir, { branchPrefix: "-x/" })).rejects.toThrow()
  expect(await launcherPrefs(p, "owner", dir)).toEqual({ workIn: "worktree", branchPrefix: "a/" })
})
