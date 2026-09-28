import { test, expect, afterEach, setDefaultTimeout } from "bun:test"
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { codex } from "../src/codex/index.js"
import { CATALOG_FILE, multiAgentV1Args, pinCatalogToV1 } from "../src/codex/catalog.js"
import type { DriverContext } from "../src/types.js"

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL("./fixtures/codex-agent.mjs", import.meta.url))
const dirs: string[] = []
const temp = (prefix: string) => { const dir = mkdtempSync(join(tmpdir(), prefix)); dirs.push(dir); return dir }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })

const CATALOG = JSON.stringify({ models: [
  { slug: "gpt-6-astra", multi_agent_version: "v2", multi_agent_reasoning_effort: "xhigh", base_instructions: "x" },
  { slug: "gpt-5.6-luna", multi_agent_version: "v1" },
  { slug: "gpt-5.5" },
] })

/** A stand-in `codex`: `debug models` prints the catalog (or fails), `app-server …` records argv and runs the fixture. */
function fakeCodex(debug: "ok" | "fail" | "no-v2"): string {
  const dir = temp("fake-codex-")
  const catalog = debug === "no-v2" ? JSON.stringify({ models: [{ slug: "m", multi_agent_version: "v1" }] }) : CATALOG
  writeFileSync(join(dir, "catalog.json"), catalog)
  const script = join(dir, "codex")
  writeFileSync(script, `#!/bin/sh
if [ "$1" = "debug" ]; then
  ${debug === "fail" ? "exit 3" : `cat "${join(dir, "catalog.json")}"; exit 0`}
fi
printf '%s\\n' "$@" > "$CODEX_HOME/argv.txt"
exec "${process.execPath}" "${fixture}"
`)
  chmodSync(script, 0o755)
  return script
}

test("pinCatalogToV1 rewrites only v2 entries and keeps everything else", () => {
  const pinned = JSON.parse(pinCatalogToV1(CATALOG)!)
  expect(pinned.models.map((m: any) => m.multi_agent_version)).toEqual(["v1", "v1", undefined])
  expect(pinned.models[0]).toMatchObject({ slug: "gpt-6-astra", multi_agent_reasoning_effort: "xhigh", base_instructions: "x" })
  expect(pinCatalogToV1(JSON.stringify({ models: [{ slug: "a", multi_agent_version: "v1" }] }))).toBeUndefined()
  expect(pinCatalogToV1("not json")).toBeUndefined()
  expect(pinCatalogToV1(JSON.stringify({ models: [] }))).toBeUndefined()
})

test("multiAgentV1Args writes a private v1 catalog and passes it as a process flag", async () => {
  const home = temp("codex-home-")
  const args = await multiAgentV1Args(fakeCodex("ok"), ["app-server", "--listen", "stdio://"], { ...process.env, CODEX_HOME: home }, home)
  const path = join(home, CATALOG_FILE)
  expect(args).toEqual(["app-server", "-c", `model_catalog_json=${JSON.stringify(path)}`, "--listen", "stdio://"])
  expect(JSON.parse(readFileSync(path, "utf8")).models[0].multi_agent_version).toBe("v1")
  expect(statSync(path).mode & 0o777).toBe(0o600)
})

test("multiAgentV1Args leaves args alone when it cannot or need not pin", async () => {
  const home = temp("codex-home-")
  const env = { ...process.env, CODEX_HOME: home }
  const base = ["app-server"]
  expect(await multiAgentV1Args(fakeCodex("fail"), base, env, home)).toBe(base)
  expect(await multiAgentV1Args(fakeCodex("no-v2"), base, env, home)).toBe(base)
  expect(await multiAgentV1Args("/not-a-codex-executable", base, env, home)).toBe(base)
  const { CODEX_HOME: _, ...noHome } = env
  expect(await multiAgentV1Args(fakeCodex("ok"), base, noHome, home)).toBe(base)
  const fixtureArgs = [fixture]
  expect(await multiAgentV1Args(fakeCodex("ok"), fixtureArgs, env, home)).toBe(fixtureArgs)
  const explicit = ["app-server", "-c", "model_catalog_json=\"/x.json\""]
  expect(await multiAgentV1Args(fakeCodex("ok"), explicit, env, home)).toBe(explicit)
  expect(existsSync(join(home, CATALOG_FILE))).toBe(false)
})

function ctx(): DriverContext {
  return { sessionId: "core", cwd: process.cwd(), signal: new AbortController().signal, onUpdate() {}, onExit() {}, requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }) }
}
function driver(command: string, home: string) {
  const stateDirectory = temp("codex-keeper-")
  return codex({ id: "codex", command, args: ["app-server"], env: { CODEX_HOME: home }, inheritEnv: true, sandbox: "read-only", approvalPolicy: "never", permissionPrompts: "none", permissions: { kind: "codex", approvalPolicy: "never", sandbox: "read-only" }, setupTimeoutMs: 5000, requestTimeoutMs: 3000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024, keeper: { stateDirectory, limits: { parkedDeadlineMs: 5000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } } })
}

test("driver starts the app-server with the v1 catalog override (start and resume)", async () => {
  const home = temp("codex-home-")
  const command = fakeCodex("ok")
  const flag = `model_catalog_json=${JSON.stringify(join(home, CATALOG_FILE))}`
  const started = await driver(command, home).open(ctx())
  try { expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", "-c", flag, ""]) } finally { await started.close({ mode: "shutdown" }) }
  rmSync(join(home, "argv.txt"))
  const resumed = await driver(command, home).open({ ...ctx(), resumeId: "old" })
  try {
    expect(resumed.agentSessionId).toBe("old")
    expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", "-c", flag, ""])
  } finally { await resumed.close({ mode: "shutdown" }) }
})

test("driver still opens without the override when the catalog dump fails", async () => {
  const home = temp("codex-home-")
  const r = await driver(fakeCodex("fail"), home).open(ctx())
  try { expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", ""]) } finally { await r.close({ mode: "shutdown" }) }
})
