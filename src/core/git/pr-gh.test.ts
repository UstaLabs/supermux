import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync, chmodSync } from "fs"
import { tmpdir } from "os"; import { join } from "path"
import { openPullRequest } from "./pr"

function fakeGhDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-fakegh-"))
  const gh = join(dir, "gh")
  writeFileSync(gh, [
    "#!/bin/sh",
    'case "$1" in',
    '  --version) echo "gh version 2.0.0 (fake)";;',
    "  auth) exit 0;;",
    '  pr) echo "https://github.com/acme/widgets/pull/42";;',
    "  *) exit 0;;",
    "esac",
    "",
  ].join("\n"))
  chmodSync(gh, 0o755)
  return dir
}
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-prgh-"))
  execFileSync("git", ["init", "-b", "main", dir])
  return dir
}

test("openPullRequest returns opened + url when gh is available", () => {
  const repo = tmpRepo()
  const orig = process.env.PATH
  process.env.PATH = fakeGhDir() + ":" + orig
  try {
    const r = openPullRequest(repo, { title: "t", body: "b", base: "main", draft: false })
    expect(r.status).toBe("opened")
    if (r.status === "opened") { expect(r.url).toBe("https://github.com/acme/widgets/pull/42"); expect(r.draft).toBe(false) }
  } finally { process.env.PATH = orig }
})

test("openPullRequest reports gh_unavailable when gh is not on PATH", () => {
  const repo = tmpRepo()
  const orig = process.env.PATH
  process.env.PATH = "/nonexistent-dir-for-test"   // no gh here; openPullRequest makes no git calls
  try {
    expect(openPullRequest(repo, { title: "t", body: "b", base: "main", draft: false }).status).toBe("gh_unavailable")
  } finally { process.env.PATH = orig }
})

test("openPullRequest passes --draft through to gh", () => {
  const repo = tmpRepo()
  const orig = process.env.PATH
  process.env.PATH = fakeGhDir() + ":" + orig
  try {
    const r = openPullRequest(repo, { title: "t", body: "b", base: "main", draft: true })
    expect(r.status).toBe("opened")
    if (r.status === "opened") expect(r.draft).toBe(true)
  } finally { process.env.PATH = orig }
})
