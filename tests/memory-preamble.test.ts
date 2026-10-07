import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { initMux } from "../src/core/memory/init"
import { buildMemoryPreamble } from "../src/core/memory/preamble"

let tmp: string

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "agentmux-prm-"))
  process.env.MUX_HOME = tmp
  initMux(tmp)
  writeFileSync(
    join(tmp, "domains", "ios-webkit.md"),
    "---\ndescription: iOS Safari PWA push gotchas\ntags: [ios]\n---\n\n# iOS WebKit\n"
  )
})

afterEach(() => {
  rmSync(tmp, { recursive: true, force: true })
  delete process.env.MUX_HOME
})

test("worker preamble inlines the live domain index (no go-read pointer)", () => {
  const out = buildMemoryPreamble("worker")
  expect(out).toContain("ios-webkit: iOS Safari PWA push gotchas")
})

test("worker preamble never references soul.md or personal/", () => {
  const out = buildMemoryPreamble("worker")
  expect(out).not.toContain("soul.md")
  expect(out).not.toContain("personal/")
})

test("main preamble inlines the index, the shared soul and points to personal/", () => {
  writeFileSync(join(tmp, "soul.md"), "SHARED-SOUL")
  const out = buildMemoryPreamble("main")
  expect(out).toContain("ios-webkit: iOS Safari PWA push gotchas")
  expect(out).toContain("SHARED-SOUL")
  expect(out).toContain("personal/")
})

// --- workdir personality files ---

test("a workdir soul.md replaces the shared soul", () => {
  writeFileSync(join(tmp, "soul.md"), "SHARED-SOUL")
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe helpful.")
  const out = buildMemoryPreamble("main", workdir)
  expect(out).toContain("Be helpful.")
  expect(out).not.toContain("SHARED-SOUL")
})

test("worker preamble never injects soul.md even when present in workdir", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe helpful.")
  const out = buildMemoryPreamble("worker", workdir)
  expect(out).not.toContain("Be helpful.")
  expect(out).not.toContain("soul.md")
})

test("preamble injects workdir focus.md after the soul", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe kind.")
  writeFileSync(join(workdir, "focus.md"), "## Current Focus\nShip it.")
  expect(buildMemoryPreamble("worker", workdir)).toContain("Ship it.")
  const out = buildMemoryPreamble("main", workdir)
  expect(out.indexOf("Be kind.")).toBeLessThan(out.indexOf("Ship it."))
})

test("preamble omits focus section when workdir focus.md is absent", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  expect(buildMemoryPreamble("worker", workdir)).not.toContain("Current Focus")
})
