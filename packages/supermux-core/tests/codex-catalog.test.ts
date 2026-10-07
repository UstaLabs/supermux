import { test, expect, afterEach, setDefaultTimeout } from "bun:test"
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { codex } from "../src/codex/index.js"
import { CATALOG_FILE, inspectCatalog, multiAgentV1Args, multiAgentV1Launch, pinCatalogToV1 } from "../src/codex/catalog.js"
import { createCore } from "../src/index.js"
import type { CoreEvent } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"
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

/** The session policy the driver appends per process (C3). */
const POLICY = ["-c", 'sandbox_mode="read-only"', "-c", 'approval_policy="never"']

/** A stand-in `codex`: `debug models` prints the catalog (or fails), `app-server …` records argv and runs the fixture. */
function fakeCodex(debug: "ok" | "fail" | "no-v2" | "no-field"): string {
  const dir = temp("fake-codex-")
  const catalog = debug === "no-v2" ? JSON.stringify({ models: [{ slug: "m", multi_agent_version: "v1" }] })
    : debug === "no-field" ? JSON.stringify({ models: [{ slug: "gpt-6-astra", base_instructions: "x" }, { slug: "gpt-5.6-luna" }] })
    : CATALOG
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
  try { expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", "-c", flag, ...POLICY, ""]) } finally { await started.close({ mode: "shutdown" }) }
  rmSync(join(home, "argv.txt"))
  const resumed = await driver(command, home).open({ ...ctx(), resumeId: "old" })
  try {
    expect(resumed.agentSessionId).toBe("old")
    expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", "-c", flag, ...POLICY, ""])
  } finally { await resumed.close({ mode: "shutdown" }) }
})

test("driver still opens without the override when the catalog dump fails", async () => {
  const home = temp("codex-home-")
  const r = await driver(fakeCodex("fail"), home).open(ctx())
  try { expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", ...POLICY, ""]) } finally { await r.close({ mode: "shutdown" }) }
})

// ── Feature detection: the catalog shape is undocumented; a changed one is left alone, with a warning ──

test("inspectCatalog pins only the known shape and explains every other one", () => {
  expect(inspectCatalog(CATALOG).kind).toBe("pin")
  expect(inspectCatalog(JSON.stringify({ models: [{ slug: "a", multi_agent_version: "v1" }, { slug: "b" }] }))).toEqual({ kind: "v1" })
  const unknown = (raw: string) => { const r = inspectCatalog(raw); expect(r.kind).toBe("unknown"); return (r as { warning: string }).warning }
  expect(unknown(JSON.stringify({ models: [{ slug: "a" }, { slug: "b", multiAgent: { version: 2 } }] }))).toContain("no `multi_agent_version` field")
  expect(unknown(JSON.stringify({ models: [{ slug: "a", multi_agent_version: "v3" }] }))).toContain('"v3"')
  expect(unknown(JSON.stringify({ data: [{ slug: "a", multi_agent_version: "v2" }] }))).toContain("no longer lists `models`")
  expect(unknown("Error: unknown subcommand")).toContain("did not print JSON")
  for (const warning of [unknown("{}"), unknown("x")]) expect(warning).toContain("multi_agent v2")
})

test("multiAgentV1Launch skips the rewrite with a warning when the field is gone or the dump fails, and stays quiet when nothing is needed", async () => {
  const home = temp("codex-home-")
  const env = { ...process.env, CODEX_HOME: home }
  const base = ["app-server"]
  const gone = await multiAgentV1Launch(fakeCodex("no-field"), base, env, home)
  expect(gone.args).toBe(base)
  expect(gone.warning).toContain("no `multi_agent_version` field")
  const failed = await multiAgentV1Launch(fakeCodex("fail"), base, env, home)
  expect(failed.args).toBe(base)
  expect(failed.warning).toContain("`codex debug models` failed")
  expect(await multiAgentV1Launch(fakeCodex("no-v2"), base, env, home)).toEqual({ args: base })
  expect(await multiAgentV1Launch(fakeCodex("ok"), [process.execPath], env, home)).toEqual({ args: [process.execPath] })
  expect(existsSync(join(home, CATALOG_FILE))).toBe(false)
})

test("driver: a catalog without multi_agent_version starts Codex unchanged and shows the warning on the first turn only", async () => {
  const home = temp("codex-home-")
  const core = createCore({ stateDirectory: temp("codex-core-"), agents: [driver(fakeCodex("no-field"), home)], limits: TEST_LIMITS })
  try {
    const events: any[] = []
    core.subscribe((e: CoreEvent) => { if (e.type === "session.event") events.push(e.event) })
    const session = await core.sessions.create({ id: nextId("codex-catalog-"), agent: "codex", cwd: home })
    expect(readFileSync(join(home, "argv.txt"), "utf8").split("\n")).toEqual(["app-server", ...POLICY, ""])
    for (const text of ["one", "two"]) {
      const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "queue" })
      expect((await receipt.completed).status).toBe("completed")
    }
    const warnings = events.filter(e => e.kind === "warning")
    expect(warnings).toHaveLength(1)
    expect(warnings[0].message).toContain("no `multi_agent_version` field")
  } finally { await core.close({ agents: "shutdown" }) }
})
