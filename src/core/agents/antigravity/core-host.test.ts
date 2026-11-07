import { afterEach, expect, test } from "bun:test"
import { createHash } from "crypto"
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime, DriverContext } from "../../../../packages/supermux-core/src/index.js"
import type { AntigravityOptions } from "../../../../packages/supermux-core/src/antigravity/index.js"
import { SESSION_INBOX_DIR } from "../../../shared/paths"
import { createAntigravityCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function tmp(prefix: string): string {
  const d = mkdtempSync(join(tmpdir(), prefix))
  dirs.push(d)
  return d
}

/** A fake real HOME with a populated ~/.gemini (read-only for supermux). */
function fakeUserHome(): string {
  const user = tmp("agy-user-")
  mkdirSync(join(user, ".ssh"))
  writeFileSync(join(user, ".gitconfig"), "[user]\n")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  writeFileSync(join(user, ".gemini", "antigravity-cli", "antigravity-oauth-token"), "{}")
  mkdirSync(join(user, ".gemini", "config", "plugins", "user-plugin"), { recursive: true })
  writeFileSync(join(user, ".gemini", "config", "mcp_config.json"), JSON.stringify({ mcpServers: { mine: { command: "mine" } } }))
  return user
}

function snapshot(root: string): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (p: string) => {
    const st = lstatSync(p)
    if (st.isSymbolicLink()) { out[p] = `link:${readlinkSync(p)}`; return }
    if (st.isDirectory()) { out[p] = `dir:${st.mode}`; for (const e of readdirSync(p)) walk(join(p, e)); return }
    out[p] = `file:${st.mode}:${createHash("sha256").update(readFileSync(p)).digest("hex")}`
  }
  if (existsSync(root)) walk(root)
  return out
}

function fakeDriver(opens: DriverContext[]): AgentDriver {
  return {
    id: "antigravity",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? "conv-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("prepare builds the shadow HOME, leaves the real ~/.gemini untouched and hands the driver its options", async () => {
  const state = tmp("agy-host-")
  const sessionHome = join(tmp("agy-sess-"), "agy-s")
  const workdir = tmp("agy-wd-")
  const userHome = fakeUserHome()
  const before = snapshot(join(userHome, ".gemini"))
  const captured: AntigravityOptions[] = []
  const opens: DriverContext[] = []
  const host = createAntigravityCoreHost({
    stateDirectory: state,
    userHome,
    driverFactory: (options) => { captured.push(options); return fakeDriver(opens) },
  })
  const handle = host.register({
    id: "id1",
    env: {},
    extra: { sessionHome, sessionName: "agy-s", sessionId: "id1", workdir, cwd: workdir, permissionMode: "plan" },
  })
  await handle.start({ cwd: workdir })

  const shadow = join(sessionHome, "home")
  expect(lstatSync(join(shadow, ".gemini", "antigravity-cli")).isSymbolicLink()).toBe(true)
  expect(readlinkSync(join(shadow, ".ssh"))).toBe(join(userHome, ".ssh"))
  expect(snapshot(join(userHome, ".gemini"))).toEqual(before)

  expect(captured).toHaveLength(1)
  expect(captured[0]!.home).toEqual({ shadow, user: userHome })
  expect(captured[0]!.permissions).toEqual({ kind: "antigravity", mode: "plan" })
  expect(captured[0]!.args).toEqual(["--add-dir", SESSION_INBOX_DIR])
  expect(captured[0]!.env?.HOME).toBe(shadow)

  const launched = opens[0]!.sessionContext!
  expect(launched.instructions).toContain("agy-s")
  const shim = launched.mcpServers.find((server) => server.name === "mux-shim")!
  expect(shim.env).toMatchObject({ MUX_SESSION_ID: "id1", MUX_DISPLAY_NAME: "agy-s", MUX_AGENT_KIND: "antigravity" })
  expect(existsSync(join(workdir, "GEMINI.md"))).toBe(false)

  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("an unknown permission mode falls back to the bypass default", async () => {
  const state = tmp("agy-host-")
  const sessionHome = join(tmp("agy-sess-"), "agy-d")
  const workdir = tmp("agy-wd-")
  const captured: AntigravityOptions[] = []
  const host = createAntigravityCoreHost({
    stateDirectory: state,
    userHome: fakeUserHome(),
    driverFactory: (options) => { captured.push(options); return fakeDriver([]) },
  })
  const handle = host.register({ id: "id2", env: {}, extra: { sessionHome, sessionName: "agy-d", sessionId: "id2", workdir, cwd: workdir } })
  await handle.start({ cwd: workdir })
  expect(captured[0]!.permissions).toEqual({ kind: "antigravity", mode: "bypass" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("a registration without extra is rejected", async () => {
  const host = createAntigravityCoreHost({ stateDirectory: tmp("agy-host-"), userHome: fakeUserHome(), driverFactory: () => fakeDriver([]) })
  const handle = host.register({ id: "id3", env: {} })
  await expect(handle.start({ cwd: tmpdir() })).rejects.toThrow(/extra/)
  await host.close({ agents: "shutdown" }).catch(() => {})
})
