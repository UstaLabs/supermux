import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, statSync } from "node:fs"
import { rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { agyPromptBlocks, antigravity, antigravityArgs, permissionFlags, type AntigravityOptions } from "../src/antigravity/index.js"
import { sessionPreamble } from "../src/context/agents.js"
import type { LaunchContext } from "../src/context/types.js"
import { prepareAntigravityEnvironment } from "../src/environment/index.js"
import type { AgentUpdate, DriverContext } from "../src/types.js"

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL("./fixtures/antigravity-agent.mjs", import.meta.url))
const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
const scratch = () => { const dir = mkdtempSync(join(tmpdir(), "agy-driver-")); dirs.push(dir); return dir }
const signal = () => new AbortController().signal
const text = (value: string) => [{ type: "text" as const, text: value }]
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]).toString("base64")
const flag = (argv: string[], name: string) => { const i = argv.indexOf(name); return i < 0 ? undefined : argv[i + 1] }
const codeOf = (fn: () => unknown) => { try { fn() } catch (error: any) { return error?.code } return undefined }

async function until(check: () => boolean, ms = 5000) {
  const start = Date.now()
  while (!check()) { if (Date.now() - start > ms) throw new Error("timed out waiting"); await Bun.sleep(20) }
}

async function rig() {
  const base = scratch()
  const user = join(base, "user"), shadow = join(base, "shadow"), stateDirectory = join(base, "state"), log = join(base, "agy.log")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  await prepareAntigravityEnvironment({ home: shadow, userHome: user, platform: process.platform })
  const driver = (extra: Partial<AntigravityOptions> = {}) => antigravity({
    id: "antigravity", command: process.execPath, args: [fixture], env: { FAKE_AGY_LOG: log }, inheritEnv: true,
    permissions: { kind: "antigravity", mode: "bypass" }, home: { shadow, user },
    setupTimeoutMs: 5000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024,
    keeper: { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } },
    ...extra,
  })
  const entries = (): any[] => existsSync(log) ? readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line)) : []
  return {
    base, user, shadow, stateDirectory, driver,
    spawns: () => entries().filter(e => e.type === "spawn") as Array<{ argv: string[]; home: string; pid: number }>,
    inputs: () => entries().filter(e => e.type === "input").map(e => e.content) as Array<Array<{ type: string; text: string }>>,
  }
}
const ctx = (extra: Partial<DriverContext> = {}): DriverContext => ({
  sessionId: "core", cwd: process.cwd(), signal: signal(), onUpdate() {}, onExit() {},
  requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
  ...extra,
})
const withInstructions = (base: string, instructions: string, launch: LaunchContext["launch"] = "create"): LaunchContext => ({
  instructions, skills: [], plugins: [], mcpServers: [], directory: join(base, "ctx"), launch, dropped: [], fingerprint: "fp-1",
})
const replies = (updates: AgentUpdate[]) => updates.flatMap(u => {
  const step = (u.value as any)?.step_update
  return step?.step_type === "agent_response" && step.text_delta ? [step.text_delta as string] : []
})

test("new conversation: init names the session; one agy process carries every turn", async () => {
  const r = await rig()
  const updates: AgentUpdate[] = []
  const rt = await r.driver().open(ctx({ onUpdate: u => updates.push(u) }))
  try {
    expect(rt.agentSessionId).toMatch(/^[0-9a-f-]{36}$/)
    expect(rt.capabilities).toEqual({ resume: true, steer: false, fork: false, detach: true, configure: true, permissions: true })
    expect(rt.nativeProtocol).toBe("antigravity-stream-json")
    expect(await rt.prompt(text("one"), signal())).toEqual({ stopReason: "end_turn" })
    expect(await rt.prompt(text("two"), signal())).toEqual({ stopReason: "end_turn" })
    expect(replies(updates)).toEqual(["ECHO:one", "ECHO:two"])
    expect(updates.every(u => u.protocol === "native")).toBe(true)
    expect((updates[0]!.value as any).event).toBe("init")
    const spawns = r.spawns()
    expect(spawns).toHaveLength(1)
    expect(spawns[0]!.home).toBe(r.shadow)
    expect(spawns[0]!.argv).toEqual(["--input-format", "stream-json", "--output-format", "stream-json", "--dangerously-skip-permissions"])
    const statePath = join(r.stateDirectory, "antigravity", "core.json")
    expect(JSON.parse(readFileSync(statePath, "utf8"))).toEqual({ conversationId: rt.agentSessionId, preambleSent: true })
    expect(statSync(statePath).mode & 0o777).toBe(0o600)
    const echoed = updates.find(u => (u.value as any)?.step_update?.text_delta === "ECHO:one")!
    expect(rt.normalize!(echoed)).toEqual([{ kind: "assistant-delta", messageId: expect.stringMatching(/:1$/), text: "ECHO:one" }])
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("an ineligible account fails the open with agy's message", async () => {
  const r = await rig()
  await expect(r.driver({ env: { FAKE_AGY_MODE: "ineligible" } }).open(ctx())).rejects.toThrow(/Eligibility check failed/)
})

test("the preamble rides the first prompt of a new conversation only, never after an interrupt + respawn", async () => {
  const r = await rig()
  const rt = await r.driver().open(ctx({ sessionContext: withInstructions(r.base, "Be terse.") }))
  try {
    const sleeping = rt.prompt(text("SLEEP"), signal())
    await until(() => r.inputs().length === 1)
    await rt.interrupt()
    expect(await sleeping).toEqual({ stopReason: "cancelled" })
    expect(await rt.prompt(text("after"), signal())).toEqual({ stopReason: "end_turn" })
    expect(r.inputs()).toEqual([
      [{ type: "text", text: sessionPreamble("Be terse.") }, { type: "text", text: "SLEEP" }],
      [{ type: "text", text: "after" }],
    ])
    expect(r.spawns()).toHaveLength(2)
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("interrupt: SIGINT ends the turn as cancelled and the process; the next prompt respawns with --conversation", async () => {
  const r = await rig()
  const exits: Error[] = []
  const rt = await r.driver().open(ctx({ onExit: e => exits.push(e) }))
  try {
    const sleeping = rt.prompt(text("SLEEP"), signal())
    await until(() => r.inputs().length === 1)
    await rt.interrupt()
    expect(await sleeping).toEqual({ stopReason: "cancelled" })
    expect(await rt.prompt(text("again"), signal())).toEqual({ stopReason: "end_turn" })
    const [first, second] = r.spawns()
    expect(flag(first!.argv, "--conversation")).toBeUndefined()
    expect(flag(second!.argv, "--conversation")).toBe(rt.agentSessionId)
    expect(second!.pid).not.toBe(first!.pid)
    const abort = new AbortController()
    const aborted = rt.prompt(text("SLEEP"), abort.signal)
    await until(() => r.inputs().length === 3)
    abort.abort()
    expect(await aborted).toEqual({ stopReason: "cancelled" })
    expect(exits).toEqual([])
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("configure: a new model restarts an idle agy lazily with --model; the same model is a no-op", async () => {
  const r = await rig()
  const rt = await r.driver({ model: "gemini-3.8-flash-high" }).open(ctx())
  try {
    expect(flag(r.spawns()[0]!.argv, "--model")).toBe("gemini-3.8-flash-high")
    await rt.prompt(text("one"), signal())
    await rt.configure!({ model: "claude-opus-5-5-medium" })
    expect(rt.configuration!()).toEqual({ model: "claude-opus-5-5-medium" })
    expect(r.spawns()).toHaveLength(1)
    await rt.prompt(text("two"), signal())
    const second = r.spawns()[1]!.argv
    expect(flag(second, "--model")).toBe("claude-opus-5-5-medium")
    expect(flag(second, "--conversation")).toBe(rt.agentSessionId)
    await rt.configure!({ model: "claude-opus-5-5-medium" })
    await rt.prompt(text("three"), signal())
    expect(r.spawns()).toHaveLength(2)
    await rt.configure!({})
    await rt.prompt(text("four"), signal())
    expect(flag(r.spawns()[2]!.argv, "--model")).toBe("gemini-3.8-flash-high")
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("permission modes map to agy flags; setPermissions applies next turn through a respawn", async () => {
  expect(permissionFlags({ kind: "antigravity", mode: "bypass" })).toEqual(["--dangerously-skip-permissions"])
  expect(permissionFlags({ kind: "antigravity", mode: "acceptEdits" })).toEqual(["--mode", "accept-edits"])
  expect(permissionFlags({ kind: "antigravity", mode: "plan" })).toEqual(["--mode", "plan"])
  expect(permissionFlags({ kind: "antigravity", mode: "default" })).toEqual([])
  expect(antigravityArgs({ prefix: ["x"], conversationId: "c1", model: "m1", permissions: { kind: "antigravity", mode: "plan" } }))
    .toEqual(["x", "--input-format", "stream-json", "--output-format", "stream-json", "--conversation", "c1", "--model", "m1", "--mode", "plan"])
  const r = await rig()
  expect(() => r.driver({ permissions: { kind: "claude", permissionMode: "default" } as any })).toThrow(TypeError)
  const rt = await r.driver({ permissions: { kind: "antigravity", mode: "plan" } }).open(ctx())
  try {
    expect(flag(r.spawns()[0]!.argv, "--mode")).toBe("plan")
    expect(r.spawns()[0]!.argv).not.toContain("--dangerously-skip-permissions")
    expect(await rt.setPermissions!({ kind: "antigravity", mode: "default" })).toEqual({ applied: "next-turn" })
    await expect(rt.setPermissions!({ kind: "claude", permissionMode: "default" })).rejects.toThrow(TypeError)
    await rt.prompt(text("go"), signal())
    const next = r.spawns()[1]!.argv
    expect(next).not.toContain("--mode")
    expect(next).not.toContain("--dangerously-skip-permissions")
    expect(flag(next, "--conversation")).toBe(rt.agentSessionId)
  } finally { await rt.close({ mode: "shutdown" }) }
  const other = await r.driver().open(ctx({ sessionId: "core2", permissions: { kind: "antigravity", mode: "acceptEdits" } }))
  try { expect(flag(r.spawns().at(-1)!.argv, "--mode")).toBe("accept-edits") } finally { await other.close({ mode: "shutdown" }) }
})

test("an image becomes [Attached image: <path>]: a 0600 file under the session's attachments dir", async () => {
  const dir = join(scratch(), "attachments")
  const blocks = agyPromptBlocks([{ type: "text", text: "what is this?" }, { type: "image", mimeType: "image/png", data: PNG }], dir)
  expect(blocks).toHaveLength(1)
  const path = /^what is this\?\n\n\[Attached image: (.+\.png)\]$/.exec(blocks[0]!.text)![1]!
  expect(path.startsWith(dir + "/")).toBe(true)
  expect(readFileSync(path)).toEqual(Buffer.from(PNG, "base64"))
  expect(statSync(path).mode & 0o777).toBe(0o600)
  expect(codeOf(() => agyPromptBlocks([{ type: "resource_link", uri: "file:///x", name: "x" }], dir))).toBe("invalid_input")
  const r = await rig()
  const rt = await r.driver().open(ctx())
  try {
    await rt.prompt([{ type: "text", text: "look" }, { type: "image", mimeType: "image/jpeg", data: PNG }], signal())
    const file = /\[Attached image: (.+)\]$/.exec(r.inputs()[0]![0]!.text)![1]!
    expect(file.startsWith(join(r.stateDirectory, "antigravity", "core", "attachments") + "/")).toBe(true)
    expect(file.endsWith(".jpg")).toBe(true)
    expect(statSync(file).mode & 0o777).toBe(0o600)
    await expect(rt.prompt([{ type: "resource_link", uri: "file:///x", name: "x" }], signal())).rejects.toMatchObject({ code: "invalid_input" })
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("resume is lazy: no agy until the first prompt, then --conversation <id>; the preamble is never resent", async () => {
  const r = await rig()
  const first = await r.driver().open(ctx({ sessionContext: withInstructions(r.base, "Be terse.") }))
  const id = first.agentSessionId
  await first.prompt(text("hello"), signal())
  await first.close({ mode: "shutdown" })
  const resumed = await r.driver().open(ctx({ resumeId: id, sessionContext: withInstructions(r.base, "Be terse.", "resume") }))
  try {
    expect(resumed.agentSessionId).toBe(id)
    expect(r.spawns()).toHaveLength(1)
    expect(await resumed.prompt(text("again"), signal())).toEqual({ stopReason: "end_turn" })
    expect(flag(r.spawns()[1]!.argv, "--conversation")).toBe(id)
    expect(r.inputs()[1]).toEqual([{ type: "text", text: "again" }])
  } finally { await resumed.close({ mode: "shutdown" }) }
  const fresh = await r.driver().open(ctx({ sessionId: "core2", sessionContext: withInstructions(r.base, "Be terse.") }))
  const freshId = fresh.agentSessionId
  await fresh.close({ mode: "shutdown" })
  const back = await r.driver().open(ctx({ sessionId: "core2", resumeId: freshId, sessionContext: withInstructions(r.base, "Be terse.", "resume") }))
  try {
    await back.prompt(text("first"), signal())
    expect(r.inputs().at(-1)).toEqual([{ type: "text", text: sessionPreamble("Be terse.") }, { type: "text", text: "first" }])
  } finally { await back.close({ mode: "shutdown" }) }
  const adopted = await r.driver().open(ctx({ sessionId: "core3", resumeId: "abc", sessionContext: withInstructions(r.base, "Be terse.", "resume") }))
  try {
    await adopted.prompt(text("x"), signal())
    expect(flag(r.spawns().at(-1)!.argv, "--conversation")).toBe("abc")
    expect(r.inputs().at(-1)).toEqual([{ type: "text", text: "x" }])
  } finally { await adopted.close({ mode: "shutdown" }) }
})

test("detach keeps agy running; a resume re-attaches to the same process", async () => {
  const r = await rig()
  const rt = await r.driver().open(ctx())
  await rt.prompt(text("one"), signal())
  await rt.close({ mode: "detach" })
  const again = await r.driver().open(ctx({ resumeId: rt.agentSessionId }))
  try {
    expect(again.agentSessionId).toBe(rt.agentSessionId)
    expect(await again.prompt(text("two"), signal())).toEqual({ stopReason: "end_turn" })
    expect(r.spawns()).toHaveLength(1)
  } finally { await again.close({ mode: "shutdown" }) }
})

test("agy dying mid-turn fails that turn only; a result error rejects with agy's message", async () => {
  const r = await rig()
  const exits: Error[] = []
  const rt = await r.driver().open(ctx({ onExit: e => exits.push(e) }))
  try {
    await expect(rt.prompt(text("EXIT"), signal())).rejects.toThrow(/exited/)
    await expect(rt.prompt(text("FAIL"), signal())).rejects.toThrow("model exploded")
    expect(await rt.prompt(text("fine"), signal())).toEqual({ stopReason: "end_turn" })
    expect(r.spawns()).toHaveLength(2)
    expect(flag(r.spawns()[1]!.argv, "--conversation")).toBe(rt.agentSessionId)
    expect(exits).toEqual([])
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("agy dying mid-tool: the turn fails and the runtime's flush ends the open tool card as failed", async () => {
  const r = await rig()
  const updates: AgentUpdate[] = []
  const rt = await r.driver().open(ctx({ onUpdate: u => updates.push(u) }))
  try {
    await expect(rt.prompt(text("TOOLEXIT"), signal())).rejects.toThrow(/exited/)
    // Session normalizes every update as it arrives, then flushes when the turn ends.
    const bodies = updates.flatMap(u => rt.normalize!(u))
    expect(bodies.filter(b => b.kind === "tool-call").map(b => (b as any).phase)).toEqual(["started"])
    expect(rt.flush!()).toEqual([expect.objectContaining({ kind: "tool-call", tool: "run_command", phase: "failed", category: "execute" })])
    expect(rt.flush!()).toEqual([])
    expect(await rt.prompt(text("fine"), signal())).toEqual({ stopReason: "end_turn" })
  } finally { await rt.close({ mode: "shutdown" }) }
})

test("an unowned turn of a re-attached process completes as native activity", async () => {
  const r = await rig()
  const rt = await r.driver().open(ctx())
  const sleeping = rt.prompt(text("SLEEP"), signal()).catch((error: unknown) => error)
  await until(() => r.inputs().length === 1)
  await rt.close({ mode: "detach" })
  expect(await sleeping).toMatchObject({ code: "runtime_closed" })
  const activity: Array<{ id: string; phase: string }> = []
  const again = await r.driver().open(ctx({ resumeId: rt.agentSessionId, onActivity: notice => { activity.push({ id: notice.id, phase: notice.phase }) } }))
  try {
    expect(activity).toHaveLength(1)
    expect(activity[0]!.phase).toBe("started")
    await again.interrupt()
    await until(() => activity.length === 2)
    expect(activity[1]).toEqual({ id: activity[0]!.id, phase: "completed" })
    expect(await again.prompt(text("next"), signal())).toEqual({ stopReason: "end_turn" })
    expect(r.spawns()).toHaveLength(2)
  } finally { await again.close({ mode: "shutdown" }) }
})
