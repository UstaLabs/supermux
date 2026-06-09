// src/core/worktree/verify-suggest.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { suggestVerify } from "./verify-suggest"

function tmp(): string { return mkdtempSync(join(tmpdir(), "mux-sug-")) }

test("CI workflow run: step is extracted and preferred", () => {
  const dir = tmp()
  mkdirSync(join(dir, ".github", "workflows"), { recursive: true })
  writeFileSync(join(dir, ".github", "workflows", "ci.yml"),
    "jobs:\n  test:\n    steps:\n      - uses: actions/checkout@v4\n      - run: go test ./...\n")
  writeFileSync(join(dir, "go.mod"), "module x\n")
  const s = suggestVerify(dir)
  expect(s.content).toContain("go test ./...")
  expect(s.content).not.toContain("actions/checkout")
  expect(s.source).toMatch(/ci\.yml/)
})

test("manifest convention when no CI", () => {
  const dir = tmp(); writeFileSync(join(dir, "go.mod"), "module x\n")
  const s = suggestVerify(dir)
  expect(s.content).toContain("go test ./...")
  expect(s.source).toMatch(/Go/)
})

test("package.json scripts.test → pm by lockfile", () => {
  const dir = tmp()
  writeFileSync(join(dir, "package.json"), JSON.stringify({ scripts: { test: "vitest" } }))
  writeFileSync(join(dir, "bun.lockb"), "")
  expect(suggestVerify(dir).content).toContain("bun test")
})

test("no signals → commented template", () => {
  const s = suggestVerify(tmp())
  expect(s.content).toContain("No tests detected")
  expect(s.content).toMatch(/^#!\/usr\/bin\/env bash/)
})
