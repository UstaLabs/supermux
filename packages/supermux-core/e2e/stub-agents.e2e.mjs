import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile, spawnSync } from "node:child_process"
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, join } from "node:path"
import { promisify } from "node:util"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"

test("a stub hands its env values to the fixture verbatim", { skip }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const trace = join(dir, "env.json")
  const odd = "it's \"quoted\" $HOME `echo no` \\ ; & * (x)"
  const stubs = stubAgents({ ENV_TRACE: trace, ODD: odd })
  try {
    // The Claude fixture records its env before it looks at its flags, then exits 10 refusing them.
    await assert.rejects(run(join(stubs.bin, "claude"), [], { env: stubs.env }), { code: 10 })
    assert.equal(JSON.parse(readFileSync(trace, "utf8")).env.ODD, odd)
  } finally {
    stubs.cleanup()
    rmSync(dir, { recursive: true, force: true })
  }
})

test("the env reaches no real CLI and no real home", { skip }, () => {
  // A CLI of the same name further along the caller's PATH: where a real one would be installed.
  const real = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  writeFileSync(join(real, "claude"), "#!/bin/sh\nexit 0\n", { mode: 0o755 })
  const saved = process.env.PATH
  process.env.PATH = `${real}${delimiter}${saved}`
  let stubs
  try {
    stubs = stubAgents()
    const resolve = () => spawnSync("/bin/sh", ["-c", "command -v claude"], { env: stubs.env, encoding: "utf8" })
    assert.equal(resolve().stdout.trim(), join(stubs.bin, "claude"))
    // Without its stub the name resolves to nothing, rather than to the real CLI.
    rmSync(join(stubs.bin, "claude"))
    const found = resolve()
    assert.notEqual(found.status, 0, `claude still resolves to ${found.stdout.trim()}`)
    assert.notEqual(stubs.env.HOME, process.env.HOME)
    assert.deepEqual(readdirSync(stubs.env.HOME), [])
  } finally {
    process.env.PATH = saved
    stubs?.cleanup()
    rmSync(real, { recursive: true, force: true })
  }
})

test("stubAgents() removes its directory when it fails midway", { skip }, () => {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const saved = process.env.TMPDIR
  process.env.TMPDIR = work // where stubAgents() makes its directory
  try {
    assert.throws(() => stubAgents({ get BOOM() { throw new Error("boom") } }), /boom/)
    assert.deepEqual(readdirSync(work), [])
  } finally {
    if (saved === undefined) delete process.env.TMPDIR
    else process.env.TMPDIR = saved
    rmSync(work, { recursive: true, force: true })
  }
})
