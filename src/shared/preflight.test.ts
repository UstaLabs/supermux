import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, chmodSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join, delimiter } from "path"
import { checkPreflight, hasBinary } from "./preflight"

const has = (present: string[]) => (bin: string) => present.includes(bin)

test("missing tmux is a WARNING, not fatal, when an agent CLI exists", () => {
  const r = checkPreflight(has(["codex"]))
  expect(r.fatal).toEqual([])
  expect(r.warnings.some((w) => w.toLowerCase().includes("tmux"))).toBe(true)
})

// The Plan 4 cutover moved workspace terminals off tmux entirely. A tmux warning
// that still claims to disable them is not a stale comment — it is the broker
// telling a user their terminals are gone when they are not.
test("the tmux warning is about agent sessions, and says workspace terminals still work", () => {
  const r = checkPreflight(has(["codex"]))
  const tmuxWarning = r.warnings.find((w) => w.toLowerCase().includes("tmux")) ?? ""
  expect(tmuxWarning.toLowerCase()).toContain("agent sessions")
  expect(tmuxWarning.toLowerCase()).toContain("workspace terminals still work")
})

// Workspace readiness is a PACKAGING fact, not a PATH fact: the shipped zmx
// bundle either verified against its manifest or it did not, and tmux has
// nothing to do with either answer.
test("workspace terminals warn when the packaged bundle does not verify — with tmux present", () => {
  const r = checkPreflight(has(["tmux", "claude", "codex", "cursor-agent", "opencode", "grok"]), "linux", {
    ok: false,
    reason: "zmx helper binary at /opt/x/bin/mux-zmx-helper is 9f2a…, manifest says 1911…",
  })
  expect(r.fatal).toEqual([])
  const warning = r.warnings.find((w) => w.includes("Workspace terminals")) ?? ""
  expect(warning).toContain("manifest says")
  expect(warning.toLowerCase()).toContain("agent sessions are unaffected")
})

test("a verified bundle produces no workspace warning — with tmux absent", () => {
  const r = checkPreflight(has(["claude", "codex", "cursor-agent", "opencode", "grok"]), "linux", {
    ok: true,
    detail: "zmx 8bab1f0173b0 helper ABI 1 (linux-x64)",
  })
  expect(r.warnings.some((w) => w.includes("Workspace terminals"))).toBe(false)
})

test("readiness is not consulted at all when the caller does not pass it", () => {
  const r = checkPreflight(has(["claude", "codex", "cursor-agent", "opencode", "grok"]), "linux")
  expect(r.warnings.some((w) => w.includes("Workspace terminals"))).toBe(false)
})

test("no agent CLI at all is still fatal", () => {
  const r = checkPreflight(has([]))
  expect(r.fatal.length).toBeGreaterThan(0)
})

test("tmux present produces no tmux warning", () => {
  const r = checkPreflight(has(["tmux", "claude"]))
  expect(r.warnings.some((w) => w.toLowerCase().includes("tmux"))).toBe(false)
})

test("Windows uses sessiond and never warns about tmux", () => {
  const r = checkPreflight(has(["codex"]), "win32")
  expect(r.fatal).toEqual([])
  expect(r.warnings.some((w) => w.toLowerCase().includes("tmux"))).toBe(false)
})

test("all structured agents and Cursor's agent alias satisfy availability", () => {
  for (const binary of ["agent", "opencode", "grok"]) {
    expect(checkPreflight(has([binary]), "win32").fatal).toEqual([])
  }
})

const origPath = process.env.PATH
afterEach(() => {
  process.env.PATH = origPath
})

// Regression: an agent installed at runtime (via the settings install button)
// lands in a dir we prepend to process.env.PATH. Bun's execSync IGNORES an
// in-process PATH mutation unless env is passed explicitly, so hasBinary must
// pass env — otherwise a freshly-installed agent reads as not-installed.
test("hasBinary sees a binary added to process.env.PATH at runtime", () => {
  const dir = mkdtempSync(join(tmpdir(), "preflight-bin-"))
  try {
    const bin = join(dir, "mux-fake-tool")
    writeFileSync(bin, "#!/bin/sh\necho ok\n")
    chmodSync(bin, 0o755)

    expect(hasBinary("mux-fake-tool")).toBe(false) // not on PATH yet
    process.env.PATH = `${dir}${delimiter}${origPath ?? ""}`
    expect(hasBinary("mux-fake-tool")).toBe(true) // now resolvable via the mutated PATH
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
