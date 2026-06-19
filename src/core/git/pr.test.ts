import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { compareUrl } from "./pr"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function repoWithRemote(url: string): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-pr-"))
  execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "remote", "add", "origin", url)
  return dir
}

test("compareUrl builds a GitHub https compare link from an ssh remote", () => {
  const dir = repoWithRemote("git@github.com:acme/widgets.git")
  expect(compareUrl(dir, "main", "mux/foo")).toBe("https://github.com/acme/widgets/compare/main...mux/foo?expand=1")
})

test("compareUrl handles https remotes and strips .git", () => {
  const dir = repoWithRemote("https://github.com/acme/widgets.git")
  expect(compareUrl(dir, "main", "mux/foo")).toBe("https://github.com/acme/widgets/compare/main...mux/foo?expand=1")
})

test("compareUrl returns null when there is no origin", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-pr-")); execFileSync("git", ["init", "-b", "main", dir])
  expect(compareUrl(dir, "main", "mux/foo")).toBeNull()
})
