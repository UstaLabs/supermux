// #72: a PR's title and description, written by the agent from the branch's commits, or from the commits alone.
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { prInput, writePrText } from "./pr-text"

/** Real git, many spawns: slow on Windows (CI), so a longer limit than bun's 5 s. */
const REAL_GIT_MS = 30_000
const git = (d: string, ...a: string[]) => execFileSync("git", ["-C", d, ...a], { encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }).trim()

function repo() {
  const d = mkdtempSync(join(tmpdir(), "mux-prtext-"))
  git(d, "init", "-q", "-b", "dev"); git(d, "config", "user.email", "t@t"); git(d, "config", "user.name", "t"); git(d, "config", "core.autocrlf", "false")
  writeFileSync(join(d, "a.txt"), "a\n"); git(d, "add", "."); git(d, "commit", "-qm", "base")
  git(d, "checkout", "-qb", "mux/search")
  writeFileSync(join(d, "App.jsx"), "search\n"); git(d, "add", "."); git(d, "commit", "-qm", "Add a search box", "-m", "Filters by name.")
  writeFileSync(join(d, "a.txt"), "a\nb\n"); git(d, "commit", "-qam", "Keep the filter on refresh")
  return d
}

test("the input is the branch's commits (oldest first) and its files, against the base", async () => {
  const d = repo()
  const i = await prInput(d, "dev", "mux/search")
  expect(i).toMatchObject({ base: "dev", branch: "mux/search" })
  expect(i.commits).toEqual([{ subject: "Add a search box", body: "Filters by name." }, { subject: "Keep the filter on refresh", body: "" }])
  expect(i.files).toEqual([{ path: "App.jsx", added: 1, removed: 0 }, { path: "a.txt", added: 1, removed: 0 }])
}, REAL_GIT_MS)

test("the agent's answer is used when it has a title; trimmed and capped", async () => {
  const d = repo()
  const i = await prInput(d, "dev", "mux/search")
  const r = await writePrText(i, async () => ({ title: "  Search products by name  ", body: "## What\n- a box" }))
  expect(r).toEqual({ title: "Search products by name", body: "## What\n- a box", by: "agent" })
  const long = await writePrText(i, async () => ({ title: "x".repeat(300), body: "" }))
  expect(long.title.length).toBeLessThanOrEqual(120)
}, REAL_GIT_MS)

test("no agent, a wrong answer or a failure falls back to the commits", async () => {
  const d = repo()
  const i = await prInput(d, "dev", "mux/search")
  const fromCommits = { title: "Add a search box", body: "- Add a search box\n- Keep the filter on refresh", by: "commits" as const }
  expect(await writePrText(i, undefined)).toEqual(fromCommits)
  expect(await writePrText(i, async () => ({ body: "no title" }))).toEqual(fromCommits)
  expect(await writePrText(i, async () => { throw new Error("agent-rpc timeout") })).toEqual(fromCommits)
}, REAL_GIT_MS)
