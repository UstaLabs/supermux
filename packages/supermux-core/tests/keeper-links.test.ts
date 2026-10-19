// A sandboxed agent that can write where the state directory lives (its working directory, /tmp) can plant a symbolic
// link among a keeper's files. The keeper and its client run unsandboxed, as the user: following that link would
// append the agent's own frames to a file the sandbox keeps it from, such as ~/.bashrc. They never follow one.
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { spawn } from "node:child_process"
import { once } from "node:events"
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { connectKeeper, removeKeeper, type KeeperConnection } from "../src/keeper/client.js"
import { KEEPER_ENV, type KeeperLimits } from "../src/keeper/protocol.js"

setDefaultTimeout(30_000)

const echo = fileURLToPath(new URL("./fixtures/echo-agent.mjs", import.meta.url))
const keeperEntry = fileURLToPath(new URL("../src/keeper/keeper.ts", import.meta.url))
const LIMITS: KeeperLimits = { maxFrameBytes: 4096, shutdownTimeoutMs: 200, parkedDeadlineMs: 5000, journalMaxBytes: 64_000, connectTimeoutMs: 4000 }
const ORIGINAL = "# the user's own file\n"
const roots: string[] = []
const pids: number[] = []

function alive(pid: number) {
  try { process.kill(pid, 0); return true } catch { return false }
}

afterEach(() => {
  for (const pid of pids.splice(0)) if (alive(pid)) try { process.kill(pid, "SIGKILL") } catch { /* gone */ }
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

/** A scratch root: `state` (the state directory) and `outside` (what the sandbox keeps the agent from), side by side. */
function scratch() {
  const root = mkdtempSync(join(tmpdir(), "keeper-links-"))
  roots.push(root)
  const outside = join(root, "outside")
  mkdirSync(outside)
  return { root, state: join(root, "state"), outside }
}

/** A file of the user's outside the state directory, where a planted link points. */
function bait(outside: string, name = "bashrc") {
  const path = join(outside, name)
  writeFileSync(path, ORIGINAL)
  return path
}

function keeperDir(state: string, sessionId: string) {
  return join(state, "keepers", sessionId)
}

async function connect(state: string, sessionId: string, limits: KeeperLimits = LIMITS) {
  const conn = await connectKeeper({
    stateDirectory: state, sessionId, cursor: 0, limits,
    spec: { command: process.execPath, args: [echo], cwd: process.cwd(), env: { PATH: process.env.PATH ?? "" }, frameShape: "jsonrpc", captureStderr: false },
  })
  const status = JSON.parse(readFileSync(join(keeperDir(state, sessionId), "status.json"), "utf8"))
  pids.push(status.keeperPid, status.agentPid)
  return conn
}

async function echoed(conn: KeeperConnection, n: number) {
  conn.write(JSON.stringify({ cmd: "echo", n }))
  const iterator = conn.frames[Symbol.asyncIterator]()
  const step = await Promise.race([
    iterator.next(),
    new Promise<IteratorResult<never>>(resolve => setTimeout(() => resolve({ done: true, value: undefined as never }), 4000)),
  ])
  if (step.done) throw new Error(`no echo of ${n}`)
  expect(step.value.line).toContain(`"echoed":${n}`)
  return step.value
}

/** Stops a detached keeper where it is (killed after the test): it cannot rewrite a file the test is about to plant. */
function freeze(state: string, sessionId: string) {
  process.kill(JSON.parse(readFileSync(join(keeperDir(state, sessionId), "status.json"), "utf8")).keeperPid, "SIGSTOP")
}

async function until(check: () => boolean, ms = 4000) {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > ms) throw new Error("timed out")
    await new Promise(resolve => setTimeout(resolve, 20))
  }
}

test("a link planted at the journal of a running keeper is never written through", async () => {
  const { state, outside } = scratch()
  const target = bait(outside)
  const conn = await connect(state, "journal")
  const journal = join(keeperDir(state, "journal"), "journal.ndjson")
  unlinkSync(journal)
  symlinkSync(target, journal)
  await echoed(conn, 1)
  await echoed(conn, 2)
  expect(readFileSync(target, "utf8")).toBe(ORIGINAL)
  await conn.shutdown()
})

test("a link planted where the keeper stages its status is never written through", async () => {
  const { state, outside } = scratch()
  const target = bait(outside)
  const conn = await connect(state, "status")
  const dir = keeperDir(state, "status")
  symlinkSync(target, join(dir, "status.json.tmp"))
  // A meta change writes the status at once.
  conn.setMeta({ marker: 1 })
  await until(() => {
    try { return lstatSync(join(dir, "status.json")).isFile() && JSON.parse(readFileSync(join(dir, "status.json"), "utf8")).meta?.marker === 1 } catch { return false }
  })
  expect(readFileSync(target, "utf8")).toBe(ORIGINAL)
  await conn.shutdown()
})

test("a link planted where the keeper stages a journal rewrite is never written through", async () => {
  const { state, outside } = scratch()
  const target = bait(outside)
  const conn = await connect(state, "rewrite", { ...LIMITS, journalMaxBytes: 220 })
  const dir = keeperDir(state, "rewrite")
  symlinkSync(target, join(dir, "journal.ndjson.tmp"))
  for (let n = 0; n < 12; n++) conn.ack((await echoed(conn, n)).seq)
  await until(() => {
    try { return JSON.parse(readFileSync(join(dir, "status.json"), "utf8")).journalRewrites > 0 } catch { return false }
  })
  expect(readFileSync(target, "utf8")).toBe(ORIGINAL)
  expect(lstatSync(join(dir, "journal.ndjson")).isFile()).toBe(true)
  await conn.shutdown()
})

for (const [what, plant] of [
  ["an existing file", (outside: string) => bait(outside)],
  ["a file that does not exist yet", (outside: string) => join(outside, "bash_aliases")],
] as const) {
  test(`a link at the journal before launch, to ${what}: the launch is refused, the target untouched`, async () => {
    const { state, outside } = scratch()
    const target = plant(outside)
    const dir = keeperDir(state, "planted")
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    symlinkSync(target, join(dir, "journal.ndjson"))
    await expect(connect(state, "planted")).rejects.toMatchObject({ code: "state_unsafe", message: expect.stringContaining("journal.ndjson") })
    if (what === "an existing file") expect(readFileSync(target, "utf8")).toBe(ORIGINAL)
    else expect(existsSync(target)).toBe(false)
    // Nothing was started: no status, no token.
    expect(readdirSync(dir).sort()).toEqual(["journal.ndjson"])
  })
}

test("a keeper directory that is a link: the launch is refused, nothing is written where it points", async () => {
  const { state, outside } = scratch()
  mkdirSync(join(state, "keepers"), { recursive: true })
  symlinkSync(outside, keeperDir(state, "linked"))
  await expect(connect(state, "linked")).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(outside)).toEqual([])
})

test("a keepers directory that is a link: the launch is refused, nothing is made where it points", async () => {
  const { state, outside } = scratch()
  mkdirSync(state)
  symlinkSync(outside, join(state, "keepers"))
  await expect(connect(state, "behind")).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(outside)).toEqual([])
})

for (const name of ["journal.ndjson", "token", "status.json"]) {
  test(`re-attaching to a keeper whose ${name} was replaced by a link is refused, the target untouched`, async () => {
    const { state, outside } = scratch()
    const target = bait(outside)
    const conn = await connect(state, "again")
    await echoed(conn, 1)
    await conn.detach()
    freeze(state, "again")
    const path = join(keeperDir(state, "again"), name)
    unlinkSync(path)
    symlinkSync(target, path)
    await expect(connect(state, "again")).rejects.toMatchObject({ code: "state_unsafe", message: expect.stringContaining(name) })
    expect(readFileSync(target, "utf8")).toBe(ORIGINAL)
  })
}

test("re-attaching to a keeper whose directory was replaced by a link is refused", async () => {
  const { state, outside } = scratch()
  const conn = await connect(state, "moved")
  await conn.detach()
  freeze(state, "moved")
  const dir = keeperDir(state, "moved")
  const moved = join(outside, "moved")
  renameSync(dir, moved)
  symlinkSync(moved, dir)
  const before = readdirSync(moved).sort()
  await expect(connect(state, "moved")).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readdirSync(moved).sort()).toEqual(before)
})

test("forget never deletes through a keepers directory that is a link", async () => {
  const { state, outside } = scratch()
  mkdirSync(state)
  mkdirSync(join(outside, "gone"))
  writeFileSync(join(outside, "gone", "precious"), ORIGINAL)
  symlinkSync(outside, join(state, "keepers"))
  await expect(removeKeeper({ stateDirectory: state, sessionId: "gone", connectTimeoutMs: 500 })).rejects.toMatchObject({ code: "state_unsafe" })
  expect(readFileSync(join(outside, "gone", "precious"), "utf8")).toBe(ORIGINAL)
})

// The client checks before it starts a keeper; the keeper checks again (a link may be planted in between).
for (const [what, plant] of [
  ["its session directory is a link", (dir: string, outside: string) => { mkdirSync(dirname(dir), { recursive: true }); symlinkSync(outside, dir) }],
  ["its journal is a link", (dir: string, outside: string) => { mkdirSync(dir, { recursive: true, mode: 0o700 }); symlinkSync(join(outside, "bash_aliases"), join(dir, "journal.ndjson")) }],
] as const) {
  test(`the keeper itself refuses to start when ${what}, and writes nothing outside`, async () => {
    const { state, outside } = scratch()
    const dir = keeperDir(state, "direct")
    plant(dir, outside)
    const child = spawn(process.execPath, [keeperEntry], {
      stdio: ["ignore", "ignore", "pipe"],
      env: {
        PATH: process.env.PATH ?? "",
        [KEEPER_ENV.sessionDir]: dir,
        [KEEPER_ENV.command]: process.execPath,
        [KEEPER_ENV.args]: JSON.stringify([echo]),
        [KEEPER_ENV.cwd]: process.cwd(),
        [KEEPER_ENV.agentEnv]: JSON.stringify({ PATH: process.env.PATH ?? "" }),
        [KEEPER_ENV.token]: "direct-token",
        [KEEPER_ENV.limits]: JSON.stringify(LIMITS),
        [KEEPER_ENV.frameShape]: "jsonrpc",
        [KEEPER_ENV.captureStderr]: "false",
      },
    })
    pids.push(child.pid!)
    let stderr = ""
    child.stderr!.setEncoding("utf8").on("data", chunk => { stderr += chunk })
    const exited = await Promise.race([once(child, "exit"), new Promise<null>(resolve => setTimeout(() => resolve(null), 5000))])
    expect(exited).not.toBeNull()
    expect(exited![0]).toBe(1)
    expect(stderr).toMatch(/symbolic link/)
    expect(readdirSync(outside)).toEqual([])
  })
}
