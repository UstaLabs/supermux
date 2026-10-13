// A host that embeds the core in a `bun build --compile` binary has no keeper.js / bridge.js on disk.
// v0.12.0 resolved them as sibling files and every session start failed with "keeper entry not found".
import { afterAll, expect, setDefaultTimeout, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { CORE_SUBCOMMANDS, isCompiledModuleUrl, selfEntry } from "../src/self-entry.js"
import { bridgeEntry } from "../src/mcp/host.js"

setDefaultTimeout(60_000)
const work = mkdtempSync(join(tmpdir(), "core-compiled-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

test("module urls inside a compiled binary are recognised on every OS", () => {
  expect(isCompiledModuleUrl("file:///$bunfs/root/supermux")).toBe(true)
  expect(isCompiledModuleUrl("file:///B:/~BUN/root/supermux.exe")).toBe(process.platform === "win32")
  expect(isCompiledModuleUrl(import.meta.url)).toBe(false)
})

test("compiled: helpers start as `<binary> <subcommand>` from a real directory", () => {
  const entry = selfEntry("file:///$bunfs/root/supermux", "keeper", CORE_SUBCOMMANDS.keeper)
  expect(entry.command).toBe(process.execPath)
  expect(entry.args).toEqual(["__core-keeper"])
  expect(entry.cwd.startsWith("/$bunfs")).toBe(false)
})

test("source: helpers start from their file next to the module", () => {
  expect(bridgeEntry()).toBe(fileURLToPath(new URL("../src/mcp/bridge.ts", import.meta.url)))
  expect(() => selfEntry(import.meta.url, "missing", "__x")).toThrow("missing entry not found")
})

test("a compiled host binary starts a keeper and relays frames", () => {
  const binary = join(work, process.platform === "win32" ? "host.exe" : "host")
  const host = fileURLToPath(new URL("./fixtures/compiled-host.ts", import.meta.url))
  const build = spawnSync(process.execPath, ["build", "--compile", host, "--outfile", binary], { encoding: "utf8" })
  expect(build.status, build.stderr).toBe(0)
  const run = spawnSync(binary, [], {
    encoding: "utf8",
    timeout: 30_000,
    env: {
      PATH: process.env.PATH ?? "", STATE_DIR: work, AGENT_RUNTIME: process.execPath,
      ECHO_AGENT: fileURLToPath(new URL("./fixtures/echo-agent.mjs", import.meta.url)),
    },
  })
  expect(run.stderr).not.toContain("entry not found")
  expect(run.status, run.stderr).toBe(0)
  expect(JSON.parse(run.stdout.trim())).toEqual({ welcome: true, echoed: 42 })
})
