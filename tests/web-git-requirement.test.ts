// "Git is required for hosting agents": what the web surface does with the broker's requirement.
import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, unlinkSync } from "fs"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"
import {
  GIT_HINT_LINUX, GIT_REQUIRED_MESSAGE, GitInstaller, GitRequiredError,
  type GitRequirement, type HostRequirements, type InstallGitResponse,
} from "../src/core/git/requirement"

const DEV_PATH = `/tmp/devices-git-req-${process.pid}.json`
const PORT = 18823
let ch: WebChannel
let token: string

const MISSING: GitRequirement = { ok: false, install: "manual", hint: GIT_HINT_LINUX }
const PRESENT: GitRequirement = { ok: true, install: "manual", hint: GIT_HINT_LINUX }

let git: GitRequirement
let spawnCalls: unknown[]
let resumeCalls: string[]
let paCalls: unknown[]
let draftCalls: unknown[]
let installCalls: number
let installAnswer: InstallGitResponse
let spawnThrows: Error | undefined

beforeEach(async () => {
  __resetAuthFailures()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  token = new DeviceStore(DEV_PATH).mint("d").token
  git = MISSING
  spawnCalls = []
  resumeCalls = []
  paCalls = []
  draftCalls = []
  installCalls = 0
  installAnswer = { status: 200, body: { ok: true } }
  spawnThrows = undefined
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    staticDir: undefined,
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getHostInfo: () => ({
      hostId: "h1", name: "n", platform: "linux", version: "1.5.0", protocolVersion: 1,
      gitAvailable: git.ok, requirements: { git },
    }),
    getHostRequirements: (): HostRequirements => ({ git }),
    installGit: () => { installCalls++; return installAnswer },
    spawnSession: async (args: unknown) => {
      spawnCalls.push(args)
      if (spawnThrows) throw spawnThrows
      return { id: "s1", name: "s", workdir: "/tmp", agent: "claude" }
    },
    createDraft: async (args: unknown) => { draftCalls.push(args); return { id: "d1", name: "draft" } },
    resumeFromArchive: async (id: string) => { resumeCalls.push(id); return { ok: true, name: "s" } },
    spawnPA: async (args: unknown) => { paCalls.push(args); return { name: "pa", workdir: "/tmp", agent: "claude" } },
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

const base = `http://127.0.0.1:${PORT}`
const authed = () => ({ Cookie: `cmux_token=${token}`, Origin: base, "content-type": "application/json" })

// ── GET /host ──────────────────────────────────────────────────────────────────────────────

test("GET /host carries requirements for an authed caller (relayed, so not direct loopback)", async () => {
  const body = await (await fetch(`${base}/host`, {
    headers: { Cookie: `cmux_token=${token}`, "x-forwarded-for": "203.0.113.9" },
  })).json() as any
  expect(body.requirements).toEqual({ git: MISSING })
  expect(body.gitAvailable).toBeUndefined() // still local-only
})

test("GET /host carries requirements and gitAvailable for a direct loopback caller", async () => {
  const body = await (await fetch(`${base}/host`)).json() as any
  expect(body.requirements).toEqual({ git: MISSING })
  expect(body.gitAvailable).toBe(false)
})

test("GET /host hides requirements from a public (relayed, unauthenticated) caller", async () => {
  const body = await (await fetch(`${base}/host`, { headers: { "x-forwarded-for": "203.0.113.9" } })).json() as any
  expect(body.requirements).toBeUndefined()
})

// ── refusing agent sessions ────────────────────────────────────────────────────────────────

test("POST /sessions is refused with 409, the message and the requirement; nothing spawns", async () => {
  const res = await fetch(`${base}/sessions`, { method: "POST", headers: authed(), body: JSON.stringify({ workdir: "/tmp" }) })
  expect(res.status).toBe(409)
  expect(await res.json()).toEqual({ error: GIT_REQUIRED_MESSAGE, code: "git_required", requirements: { git: MISSING } })
  expect(spawnCalls).toHaveLength(0)
})

test("a draft (no agent process) is still allowed while git is missing", async () => {
  const res = await fetch(`${base}/sessions`, {
    method: "POST", headers: authed(), body: JSON.stringify({ workdir: "/tmp", userStatus: "draft" }),
  })
  expect(res.status).toBe(200)
  expect(draftCalls).toHaveLength(1)
})

test("POST /sessions spawns once git is there", async () => {
  git = PRESENT
  const res = await fetch(`${base}/sessions`, { method: "POST", headers: authed(), body: JSON.stringify({ workdir: "/tmp" }) })
  expect(res.status).toBe(200)
  expect(spawnCalls).toHaveLength(1)
})

test("a GitRequiredError from the spawn path (git vanished mid-request) maps to the same 409", async () => {
  git = PRESENT
  spawnThrows = new GitRequiredError({ git: MISSING })
  const res = await fetch(`${base}/sessions`, { method: "POST", headers: authed(), body: JSON.stringify({ workdir: "/tmp" }) })
  expect(res.status).toBe(409)
  expect((await res.json() as any).requirements).toEqual({ git: MISSING })
})

test("resuming an archived session is refused while git is missing", async () => {
  const res = await fetch(`${base}/sessions/abc/resume`, { method: "POST", headers: authed(), body: "{}" })
  expect(res.status).toBe(409)
  expect((await res.json() as any).error).toBe(GIT_REQUIRED_MESSAGE)
  expect(resumeCalls).toHaveLength(0)
})

test("creating a personal assistant is refused while git is missing", async () => {
  const res = await fetch(`${base}/api/pas`, { method: "POST", headers: authed(), body: JSON.stringify({ name: "pa-test-git" }) })
  expect(res.status).toBe(409)
  expect(paCalls).toHaveLength(0)
})

// ── POST /system/install-git ───────────────────────────────────────────────────────────────

test("POST /system/install-git needs auth", async () => {
  const res = await fetch(`${base}/system/install-git`, { method: "POST", headers: { Origin: base, "content-type": "application/json" }, body: "{}" })
  expect(res.status).toBe(401)
  expect(installCalls).toBe(0)
})

test("POST /system/install-git refuses a cross-origin request", async () => {
  const res = await fetch(`${base}/system/install-git`, {
    method: "POST", headers: { Cookie: `cmux_token=${token}`, Origin: "https://evil.com", "content-type": "application/json" }, body: "{}",
  })
  expect(res.status).toBe(403)
  expect(installCalls).toBe(0)
})

test("POST /system/install-git returns the installer's answer: ok, or 400 manual + hint", async () => {
  let res = await fetch(`${base}/system/install-git`, { method: "POST", headers: authed(), body: "{}" })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true })

  installAnswer = new GitInstaller({
    platform: "linux", requirement: () => MISSING, hasWinget: () => false, spawn: () => ({ onExit: () => {} }),
  }).install()
  res = await fetch(`${base}/system/install-git`, { method: "POST", headers: authed(), body: "{}" })
  expect(res.status).toBe(400)
  expect(await res.json()).toEqual({ error: "manual", hint: GIT_HINT_LINUX })
  expect(installCalls).toBe(2)
})

test("a second click while the installer is up answers ok + inProgress", async () => {
  installAnswer = { status: 200, body: { ok: true, inProgress: true } }
  const res = await fetch(`${base}/system/install-git`, { method: "POST", headers: authed(), body: "{}" })
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ ok: true, inProgress: true })
})

// ── the host_requirements frame ────────────────────────────────────────────────────────────

test("a subscribing client gets host_requirements right after the snapshot", async () => {
  const ws = await new Promise<WebSocket>((resolve, reject) => {
    const w = new WebSocket(`ws://127.0.0.1:${PORT}/ws`, { headers: { Cookie: `cmux_token=${token}` } } as any)
    w.onopen = () => resolve(w)
    w.onerror = (e) => reject(e)
  })
  const frames: any[] = []
  const done = new Promise<void>((resolve) => {
    ws.onmessage = (e) => {
      const f = JSON.parse(String(e.data))
      if (f.type === "snapshot" || f.type === "host_requirements") frames.push(f)
      if (frames.length === 2) resolve()
    }
  })
  ws.send(JSON.stringify({ type: "subscribe" }))
  await done
  expect(frames.map((f) => f.type)).toEqual(["snapshot", "host_requirements"])
  expect(frames[1].requirements).toEqual({ git: MISSING })
  ws.close()
})
