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
  expect(out).toContain("You are a worker agent")
  // The index is inlined verbatim — the domain's description appears in-prompt.
  expect(out).toContain("ios-webkit: iOS Safari PWA push gotchas")
})

test("worker preamble never references soul.md or personal/", () => {
  const out = buildMemoryPreamble("worker")
  expect(out).not.toContain("soul.md")
  expect(out).not.toContain("personal/")
})

test("worker preamble still points at on-demand domain reads + write-back", () => {
  const out = buildMemoryPreamble("worker")
  expect(out).toContain("domains/")
})

test("main preamble inlines the index AND points to soul.md + personal/", () => {
  const out = buildMemoryPreamble("main")
  expect(out).toContain("You are the main agent")
  expect(out).toContain("ios-webkit: iOS Safari PWA push gotchas")
  expect(out).toContain("soul.md")
  expect(out).toContain("personal/")
})

test("preamble states the session's own name emphatically when provided", () => {
  const main = buildMemoryPreamble("main", "chewy")
  // Explicit name instruction (overrides Claude's "Claude Code" default) …
  expect(main).toContain('Your name is "chewy"')
  expect(main).toContain('answer "chewy"')
  // … plus the role line reinforces it.
  expect(main).toContain('You are "chewy", the main agent')
  expect(buildMemoryPreamble("worker", "scout")).toContain('You are "scout", a worker agent')
  // Unnamed (codex/cursor path) keeps the original wording, no name instruction.
  const unnamed = buildMemoryPreamble("main")
  expect(unnamed).toContain("You are the main agent")
  expect(unnamed).not.toContain("Your name is")
})

// --- workdir personality files ---

test("main preamble injects workdir soul.md when present", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe helpful.")
  const out = buildMemoryPreamble("main", "chewy", workdir)
  expect(out).toContain("Be helpful.")
  // The shared soul reference is replaced by the injected content.
  expect(out).not.toContain(`${tmp}/soul.md`)
})

test("main preamble references shared soul when workdir soul.md is absent", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  const out = buildMemoryPreamble("main", "chewy", workdir)
  expect(out).toContain("soul.md")
})

test("worker preamble never injects soul.md even when present in workdir", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe helpful.")
  const out = buildMemoryPreamble("worker", "scout", workdir)
  expect(out).not.toContain("Be helpful.")
  expect(out).not.toContain("soul.md")
})

test("preamble injects workdir focus.md when present", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "focus.md"), "## Current Focus\nFix the bug.")
  const out = buildMemoryPreamble("worker", "scout", workdir)
  expect(out).toContain("Fix the bug.")
})

test("main preamble injects both soul.md and focus.md when present", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe kind.")
  writeFileSync(join(workdir, "focus.md"), "## Current Focus\nShip it.")
  const out = buildMemoryPreamble("main", "chewy", workdir)
  expect(out).toContain("Be kind.")
  expect(out).toContain("Ship it.")
  // Soul comes before focus.
  expect(out.indexOf("Be kind.")).toBeLessThan(out.indexOf("Ship it."))
})

test("preamble omits focus section when workdir focus.md is absent", () => {
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  const out = buildMemoryPreamble("worker", "scout", workdir)
  expect(out).not.toContain("Current Focus")
})

test("preamble stays unchanged when workdir is omitted", () => {
  const out = buildMemoryPreamble("main", "chewy")
  expect(out).toContain("soul.md")
  expect(out).not.toContain("Current Focus")
})
