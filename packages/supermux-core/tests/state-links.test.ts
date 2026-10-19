// The writers in the state directory other than the store and the keepers (Antigravity's own session files) never
// reach a file through a symbolic link planted there: an agent able to write the state directory could otherwise have
// the core, unsandboxed, write or delete where the link points.
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { agyPromptBlocks, antigravity, type AntigravityOptions } from "../src/antigravity/index.js"
import { removeAntigravitySession } from "../src/antigravity/files.js"
import { prepareAntigravityEnvironment } from "../src/environment/index.js"
import type { DriverContext } from "../src/types.js"
import { WORKDIR } from "./helpers.js"

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL("./fixtures/antigravity-agent.mjs", import.meta.url))
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]).toString("base64")
const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

/** `state` (a state directory) and `outside` (where a planted link points, with a file that must stay as it is), side by side. */
function scratch() {
  const root = mkdtempSync(join(tmpdir(), "state-links-"))
  roots.push(root)
  const state = join(root, "state")
  const outside = join(root, "outside")
  mkdirSync(state)
  mkdirSync(outside)
  const victim = join(outside, "victim")
  writeFileSync(victim, "keep")
  return { root, state, outside, victim }
}

/** What `outside` holds, to compare before and after. */
const contents = (dir: string) => readdirSync(dir).sort().map(name => [name, lstatSync(join(dir, name)).isFile() ? readFileSync(join(dir, name), "utf8") : "dir"])

// ---------------------------------------------------------------- Antigravity's own files

async function agyRig() {
  const paths = scratch()
  const user = join(paths.root, "user"), shadow = join(paths.root, "shadow"), log = join(paths.root, "agy.log")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  await prepareAntigravityEnvironment({ home: shadow, userHome: user, platform: process.platform })
  const options: AntigravityOptions = {
    id: "antigravity", command: process.execPath, args: [fixture], env: { FAKE_AGY_LOG: log }, inheritEnv: true,
    permissions: { kind: "antigravity", mode: "bypass" }, home: { shadow, user },
    setupTimeoutMs: 5000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024, interruptGraceMs: 5000,
    keeper: { stateDirectory: paths.state, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } },
  }
  const ctx = (extra: Partial<DriverContext> = {}): DriverContext => ({
    sessionId: "agy", cwd: WORKDIR, signal: new AbortController().signal, onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
    ...extra,
  })
  return { ...paths, driver: antigravity(options), ctx }
}

test("Antigravity: an antigravity folder replaced by a link: the new conversation's state is refused, nothing is written where it points", async () => {
  const r = await agyRig()
  symlinkSync(r.outside, join(r.state, "antigravity"))
  await expect(r.driver.open(r.ctx())).rejects.toMatchObject({ code: "state_unsafe" })
  expect(contents(r.outside)).toEqual([["victim", "keep"]])
})

test("Antigravity: a state file that is a link is replaced, never written through; on resume it is refused, never read through", async () => {
  const r = await agyRig()
  mkdirSync(join(r.state, "antigravity"))
  const file = join(r.state, "antigravity", "agy.json")
  symlinkSync(r.victim, file)
  const runtime = await r.driver.open(r.ctx())
  await runtime.close({ mode: "shutdown" })
  expect(readFileSync(r.victim, "utf8")).toBe("keep")
  expect(lstatSync(file).isFile()).toBe(true)
  expect(JSON.parse(readFileSync(file, "utf8")).conversationId).toBe(runtime.agentSessionId)

  rmSync(file)
  writeFileSync(r.victim, JSON.stringify({ conversationId: runtime.agentSessionId, preambleSent: false }))
  symlinkSync(r.victim, file)
  await expect(r.driver.open(r.ctx({ resumeId: runtime.agentSessionId }))).rejects.toMatchObject({ code: "state_unsafe" })
  expect(lstatSync(file).isSymbolicLink()).toBe(true)
})

for (const [where, plant] of [
  ["the session's folder", (state: string, outside: string) => { mkdirSync(join(state, "antigravity"), { recursive: true }); symlinkSync(outside, join(state, "antigravity", "agy")) }],
  ["its attachments folder", (state: string, outside: string) => { mkdirSync(join(state, "antigravity", "agy"), { recursive: true }); symlinkSync(outside, join(state, "antigravity", "agy", "attachments")) }],
] as const) {
  test(`Antigravity: ${where} replaced by a link: an image prompt is refused and nothing is written where it points`, async () => {
    const r = await agyRig()
    const runtime = await r.driver.open(r.ctx())
    try {
      plant(r.state, r.outside)
      await expect(runtime.prompt([{ type: "text", text: "look" }, { type: "image", mimeType: "image/png", data: PNG }], new AbortController().signal))
        .rejects.toMatchObject({ code: "state_unsafe" })
      expect(contents(r.outside)).toEqual([["victim", "keep"]])
    } finally { await runtime.close({ mode: "shutdown" }) }
  })
}

test("Antigravity: agyPromptBlocks never writes an image through an attachments folder that is a link", () => {
  const { root, outside } = scratch()
  const dir = join(root, "attachments")
  symlinkSync(outside, dir)
  expect(() => agyPromptBlocks([{ type: "image", mimeType: "image/png", data: PNG }], dir)).toThrow(expect.objectContaining({ code: "state_unsafe" }))
  expect(contents(outside)).toEqual([["victim", "keep"]])
})

test("Antigravity: forget never deletes through an antigravity folder replaced by a link", async () => {
  const { state, outside } = scratch()
  writeFileSync(join(outside, "agy.json"), "keep")
  mkdirSync(join(outside, "agy"))
  writeFileSync(join(outside, "agy", "image.png"), "keep")
  symlinkSync(outside, join(state, "antigravity"))
  await expect(removeAntigravitySession(state, "agy")).rejects.toMatchObject({ code: "state_unsafe" })
  expect(contents(outside)).toEqual([["agy", "dir"], ["agy.json", "keep"], ["victim", "keep"]])
  expect(readFileSync(join(outside, "agy", "image.png"), "utf8")).toBe("keep")
})
