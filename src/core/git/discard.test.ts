// #72: Discard throws a file's unstaged edits away (VS Code's "Discard Changes"); what is staged stays.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { discard } from "./discard"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000

const git = (dir: string, ...a: string[]) => execFileSync("git", ["-C", dir, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()
function repo() {
  const d = mkdtempSync(join(tmpdir(), "mux-discard-"))
  git(d, "init", "-q", "-b", "dev"); git(d, "config", "user.email", "t@t"); git(d, "config", "user.name", "t")
  git(d, "config", "core.autocrlf", "false")   // Windows CI: files git writes keep "\n"
  writeFileSync(join(d, "a.txt"), "a\n"); writeFileSync(join(d, "b.txt"), "b\n")
  git(d, "add", "."); git(d, "commit", "-qm", "base")
  return d
}
const read = (d: string, f: string) => readFileSync(join(d, f), "utf-8")

test("a changed file goes back to its last commit; others are untouched", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "A\n"); writeFileSync(join(d, "b.txt"), "B\n")
  expect(await discard(d, ["a.txt"])).toEqual({ ok: true, discarded: ["a.txt"] })
  expect(read(d, "a.txt")).toBe("a\n")
  expect(read(d, "b.txt")).toBe("B\n")
}, REAL_GIT_MS)

test("staged edits stay: only what is not staged is thrown away", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "staged\n"); git(d, "add", "a.txt")
  writeFileSync(join(d, "a.txt"), "staged\nand more\n")
  await discard(d, ["a.txt"])
  expect(read(d, "a.txt")).toBe("staged\n")
  expect(git(d, "diff", "--cached", "--name-only")).toBe("a.txt")
}, REAL_GIT_MS)

test("a new file is deleted, a deleted file comes back", async () => {
  const d = repo()
  writeFileSync(join(d, "new.txt"), "n\n"); rmSync(join(d, "b.txt"))
  const r = await discard(d, ["new.txt", "b.txt"])
  expect(r.ok && r.discarded.sort()).toEqual(["b.txt", "new.txt"])
  expect(existsSync(join(d, "new.txt"))).toBe(false)
  expect(read(d, "b.txt")).toBe("b\n")
}, REAL_GIT_MS)

test("all = every unstaged change and new file, never what is staged", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "A\n"); writeFileSync(join(d, "n.txt"), "n\n")
  writeFileSync(join(d, "b.txt"), "staged b\n"); git(d, "add", "b.txt")
  const r = await discard(d, "all")
  expect(r.ok && r.discarded.sort()).toEqual(["a.txt", "n.txt"])
  expect(read(d, "a.txt")).toBe("a\n")
  expect(existsSync(join(d, "n.txt"))).toBe(false)
  expect(read(d, "b.txt")).toBe("staged b\n")
}, REAL_GIT_MS)

test("a file with no change is skipped, not an error", async () => {
  const d = repo()
  expect(await discard(d, ["a.txt", "nope.txt"])).toEqual({ ok: true, discarded: [] })
}, REAL_GIT_MS)

// `[ab].txt` as a glob would also match a.txt and b.txt.
test("paths are literal: a glob character names one file", async () => {
  const d = repo()
  writeFileSync(join(d, "a.txt"), "A\n"); writeFileSync(join(d, "[ab].txt"), "bracket\n")
  await discard(d, ["[ab].txt"])
  expect(existsSync(join(d, "[ab].txt"))).toBe(false)
  expect(read(d, "a.txt")).toBe("A\n")
}, REAL_GIT_MS)

test("a file in a conflict is refused, so a half-resolved merge is never thrown away", async () => {
  const d = repo()
  git(d, "checkout", "-qb", "other"); writeFileSync(join(d, "a.txt"), "theirs\n"); git(d, "commit", "-qam", "theirs")
  git(d, "checkout", "-q", "dev"); writeFileSync(join(d, "a.txt"), "mine\n"); git(d, "commit", "-qam", "mine")
  try { git(d, "merge", "other") } catch { /* conflict expected */ }
  expect(await discard(d, ["a.txt"])).toEqual({ ok: false, reason: "conflict", paths: ["a.txt"] })
  expect(read(d, "a.txt")).toContain("<<<<<<<")
}, REAL_GIT_MS)
