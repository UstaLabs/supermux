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

test("the memory preamble is the live domain index", () => {
  expect(buildMemoryPreamble()).toContain("ios-webkit: iOS Safari PWA push gotchas")
})

test("the memory preamble never inlines soul.md or focus.md and never names personal/", () => {
  writeFileSync(join(tmp, "soul.md"), "SHARED-SOUL")
  const workdir = join(tmp, "project")
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "focus.md"), "Ship it.")
  const out = buildMemoryPreamble()
  expect(out).not.toContain("SHARED-SOUL")
  expect(out).not.toContain("Ship it.")
  expect(out).not.toContain("personal/")
})
