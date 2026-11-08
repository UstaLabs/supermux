// Card #75: a session exists on Send. A real broker (throwaway state, MUX_TEST_BROKER=1) runs the
// library's echo fixture as `claude`. The stub waits while a HOLD file exists, so a test can keep an
// agent "starting" for as long as it needs, and logs each start's folder, so it can count wakes.
import { afterAll, afterEach, beforeAll, expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { createHmac } from "crypto"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { Registry } from "../src/core/session-manager/registry"

const REPO = join(import.meta.dir, "..")
const SEED_ID = "00000000-0000-4000-8000-000000000001"
const DOZING_ID = "00000000-0000-4000-8000-0000000000d0"
// Telegram and WhatsApp share the broker's chat inbound path (main.ts wireInbound); WhatsApp's GoWA
// API is easy to stand in for, so a dozing session is reached through it.
const CHAT_DOZING_ID = "00000000-0000-4000-8000-0000000000c0"
const WA_JID = "905550001122@s.whatsapp.net"
const WA_SECRET = "wa-secret"
const sentToChat: Array<{ phone: string; message: string }> = []
const gowa = Bun.serve({
  port: 0,
  hostname: "127.0.0.1",
  async fetch(req) {
    const path = new URL(req.url).pathname
    if (path === "/app/status") return Response.json({ results: { is_connected: true, is_logged_in: true } })
    if (path === "/send/message" && req.method === "POST") {
      sentToChat.push((await req.json()) as { phone: string; message: string })
      return Response.json({ results: { message_id: `wa-out-${sentToChat.length}` } })
    }
    return new Response("not found", { status: 404 })
  },
})
let waPort = 0

const dir = mkdtempSync(join(tmpdir(), "mux-born-asleep-"))
const state = join(dir, "state")
const HOLD = join(dir, "hold")
// While this file exists, a starting agent exits at once (a start that fails).
const FAIL = join(dir, "fail-start")
const STARTS = join(dir, "starts.log")
const project = join(dir, "project")
let broker: ReturnType<typeof Bun.spawn> | undefined
let brokerLog = ""
let base = ""
let token = ""
let ws: WebSocket | undefined
const frames: any[] = []

/** Ports nothing listens on right now, all different: each is held until every one is picked (one at a time, the
 *  kernel can hand the same free port out twice). */
function freePorts(n: number): number[] {
  const held = Array.from({ length: n }, () => Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } }))
  const ports = held.map((l) => l.port)
  for (const l of held) l.stop(true)
  return ports
}

async function waitFor<T>(what: string, probe: () => T | undefined | false | Promise<T | undefined | false>, timeoutMs = 30_000): Promise<T> {
  const until = Date.now() + timeoutMs
  for (;;) {
    const v = await probe()
    if (v) return v
    if (Date.now() > until) throw new Error(`timed out waiting for ${what}`)
    await Bun.sleep(50)
  }
}

const api = (path: string, init: RequestInit = {}) =>
  fetch(`${base}${path}`, { ...init, headers: { Authorization: `Bearer ${token}`, "content-type": "application/json", ...(init.headers ?? {}) } })

async function session(id: string): Promise<any> {
  const list = (await (await api("/sessions")).json()) as any[]
  return list.find((s) => s.id === id)
}

async function messages(id: string): Promise<any[]> {
  const body = (await (await api(`/sessions/${id}/messages`)).json()) as any
  return Array.isArray(body) ? body : body.entries ?? body.messages ?? []
}

const replies = (id: string) => frames.filter((f) => f.type === "message_append" && f.session === id && f.entry?.direction === "outbound").map((f) => f.entry.text as string)
const statuses = (id: string) => frames.filter((f) => f.type === "session_state" && f.session === id && typeof f.status === "string").map((f) => f.status as string)
const starting = (id: string) => frames.some((f) => f.type === "agent_state" && f.session === id && f.detail === "starting")
const startsIn = (folder: string) => (existsSync(STARTS) ? readFileSync(STARTS, "utf8").split("\n").filter((l) => l === folder).length : 0)
const send = (sessionId: string, text: string, clientId: string) => ws!.send(JSON.stringify({ type: "send", session: sessionId, op: "reply", args: { text }, client_message_id: clientId }))

beforeAll(async () => {
  const env: Record<string, string | undefined> = { ...process.env, MUX_HOME: dir, MUX_STATE_DIR: state, MUX_TEST_WORKDIR: join(dir, "workdir") }
  for (const k of [
    "DO_NOT_TRACK", "MUX_TELEMETRY", "MUX_TELEGRAM_BOT_TOKEN", "MUX_WHATSAPP_GOWA_URL", "MUX_WHATSAPP_GOWA_BASIC_AUTH",
    "MUX_WHATSAPP_GOWA_DEVICE_ID", "MUX_WHATSAPP_WEBHOOK_PORT", "MUX_WHATSAPP_WEBHOOK_SECRET", "MUX_RELAY_DOMAIN",
    "MUX_RELAY_BASE", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "CURSOR_API_KEY", "MUX_SHIM",
  ]) delete env[k]
  const stub = join(dir, "stubbin")
  mkdirSync(stub, { recursive: true })
  writeFileSync(join(stub, "claude"), [
    "#!/bin/sh",
    `echo "$PWD" >> "${STARTS}"`,
    `while [ -f "${HOLD}" ]; do sleep 0.05; done`,
    `if [ -f "${FAIL}" ]; then echo "failing on purpose" >&2; exit 1; fi`,
    `MODE=echo EXPECT_DEFAULT_DENY=0 FIXTURE_MCP=1 exec bun "${REPO}/packages/supermux-core/tests/fixtures/claude-agent.mjs" "$@"`,
    "",
  ].join("\n"))
  writeFileSync(join(stub, "tmux"), `#!/bin/sh\ncase "$1" in -V) echo "tmux 3.4";; esac\nexit 0\n`)
  chmodSync(join(stub, "claude"), 0o755)
  chmodSync(join(stub, "tmux"), 0o755)
  env.PATH = `${stub}:${env.PATH}`
  const seed = Bun.spawnSync(["bun", "scripts/test-broker-seed.ts"], { cwd: REPO, env: env as Record<string, string> })
  token = (JSON.parse(seed.stdout.toString()) as { token: string }).token
  // A dozing session: it has been asleep since before this boot.
  const db = openDb(join(state, "db.sqlite3"))
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  const dozingDir = join(dir, "dozing")
  mkdirSync(dozingDir)
  registry.register({ id: DOZING_ID, name: "dozing", agent: "claude", workdir: dozingDir, pid: 0, core: true, agent_home: join(state, "agents", "claude", "dozing") })
  registry.sessions.suspend(DOZING_ID)
  const chatDozingDir = join(dir, "chat-dozing")
  mkdirSync(chatDozingDir)
  registry.register({ id: CHAT_DOZING_ID, name: "chat-dozing", agent: "claude", workdir: chatDozingDir, pid: 0, core: true, agent_home: join(state, "agents", "claude", "chat-dozing") })
  registry.sessions.suspend(CHAT_DOZING_ID)
  registry.setActive(`whatsapp:${WA_JID}`, CHAT_DOZING_ID)
  db.close()
  writeFileSync(join(state, "access.json"), JSON.stringify({ whatsapp: { allowFrom: [WA_JID.split("@")[0]] } }))
  // The repo new sessions get worktrees of, and the seed session's own folder, a repo too (delegate's worktree child).
  mkdirSync(project)
  for (const repo of [project, join(dir, "workdir")]) {
    execFileSync("git", ["init", "-q", "-b", "main", repo])
    execFileSync("git", ["-C", repo, "config", "user.email", "t@t.t"])
    execFileSync("git", ["-C", repo, "config", "user.name", "t"])
    writeFileSync(join(repo, "README.md"), "hi\n")
    execFileSync("git", ["-C", repo, "add", "."])
    execFileSync("git", ["-C", repo, "commit", "-q", "-m", "init"])
  }

  // The ports are free when picked and the broker binds them a moment later. Something else can take one in between;
  // the broker then exits on EADDRINUSE at once, and is started again on new ports. Any other early exit fails here,
  // with its log, instead of after a 90 s wait.
  for (let attempt = 1; ; attempt++) {
    brokerLog = join(dir, `broker-${attempt}.log`)
    const [port, webhookPort] = freePorts(2) as [number, number]
    waPort = webhookPort
    base = `http://127.0.0.1:${port}`
    const started = Bun.spawn(["bun", "src/main.ts"], {
      cwd: REPO,
      stdout: Bun.file(brokerLog),
      stderr: Bun.file(brokerLog),
      env: {
        ...(env as Record<string, string>),
        MUX_TEST_BROKER: "1",
        MUX_SHIM: "host",
        MUX_SOCKETS_DIR: join(state, "sockets"),
        MUX_WEB_PORT: String(port),
        MUX_WEB_PUBLIC_URL: base,
        MUX_UPDATE_CHECK: "0",
        MUX_CURATOR_ENABLED: "0",
        MUX_WORKTREES_ROOT: join(dir, "worktrees"),
        MUX_WHATSAPP_GOWA_URL: `http://127.0.0.1:${gowa.port}`,
        MUX_WHATSAPP_WEBHOOK_PORT: String(waPort),
        MUX_WHATSAPP_WEBHOOK_SECRET: WA_SECRET,
      },
    })
    broker = started
    const up = await waitFor("the broker", async () => {
      if (started.exitCode !== null) return "exited" as const
      // Bounded: a port someone else holds may take the request and never answer it.
      const status = await fetch(`${base}/me`, { signal: AbortSignal.timeout(2000) }).then((r) => r.status).catch(() => 0)
      return status === 401 && ("up" as const)
    }, 90_000)
    if (up === "up") break
    const said = readFileSync(brokerLog, "utf8")
    if (!said.includes("EADDRINUSE") || attempt === 3) throw new Error(`the broker exited (${started.exitCode}) before it answered:\n${said.slice(-3000)}`)
  }
  ws = await new Promise<WebSocket>((resolve, reject) => {
    const w = new WebSocket(`${base.replace("http", "ws")}/ws?clientSurface=web`, { headers: { Authorization: `Bearer ${token}` } } as any)
    w.onopen = () => resolve(w)
    w.onerror = (e) => reject(e)
  })
  ws.onmessage = (e) => { try { frames.push(JSON.parse(String(e.data))) } catch {} }
  // The seed session is the caller of the doors' tools: wait until it answers.
  send(SEED_ID, "ready?", "web-seed-ready")
  await waitFor("the seed session's reply", () => replies(SEED_ID).includes("Fixture reply: ready?"), 60_000)
}, 120_000)

afterAll(async () => {
  ws?.close()
  gowa.stop(true)
  if (broker) { broker.kill("SIGTERM"); await broker.exited }
  if (process.env.MUX_TEST_KEEP_FIXTURE !== "1") rmSync(dir, { recursive: true, force: true })
  else console.log("fixture kept:", dir)
})

// A test that fails while an agent is held must not hold the next test's agents.
afterEach(() => { rmSync(HOLD, { force: true }); rmSync(FAIL, { force: true }) })

async function launch(body: Record<string, unknown>): Promise<{ res: Response; created: any; ms: number }> {
  const t0 = performance.now()
  const res = await api("/sessions", { method: "POST", body: JSON.stringify({ workdir: project, agent: "claude", ...body }) })
  const ms = performance.now() - t0
  return { res, created: res.ok ? await res.json() : await res.text(), ms }
}

test("the launcher answers while the agent is still starting; the first message is logged once under the app's id, then answered", async () => {
  writeFileSync(HOLD, "")
  const { res, created } = await launch({ name: "Born asleep", firstMessage: "hello", client_message_id: "web-first-1" })
  expect(res.status).toBe(200)
  // The files pane can list the session's folder right away: it waits for the folder, never a 404
  // (files still being checked out arrive through its live updates).
  expect((await api(`/fs/list?path=${encodeURIComponent(created.workdir)}`)).status).toBe(200)
  // Answered while the agent is held at its start: the row exists, asleep, with its message.
  expect(created.session_branch).toBe("mux/born-asleep")
  await waitFor("the starting state", () => starting(created.id))
  expect((await session(created.id)).status).toBe("suspended")
  const logged = (await messages(created.id)).filter((m) => m.direction === "inbound")
  expect(logged.map((m) => [m.id, m.text])).toEqual([["in:web:web-first-1", "hello"]])
  rmSync(HOLD)
  await waitFor("the reply", () => replies(created.id).includes("Fixture reply: hello"))
  // Woken: its worktree checked out on its branch, and it started once.
  expect(execFileSync("git", ["-C", created.workdir, "branch", "--show-current"], { encoding: "utf8" }).trim()).toBe("mux/born-asleep")
  expect(startsIn(created.workdir)).toBe(1)
  expect((await session(created.id)).status).toBe("active")
  // Every client hears it is awake now, not only one that takes a new snapshot.
  await waitFor("the status change", () => statuses(created.id).includes("active"))
  expect((await messages(created.id)).filter((m) => m.id === "in:web:web-first-1")).toHaveLength(1)
}, 60_000)

test("two messages sent while it starts share one wake and are answered in order", async () => {
  writeFileSync(HOLD, "")
  const { created } = await launch({ name: "Two quick", firstMessage: "one", client_message_id: "web-two-1" })
  await waitFor("the starting state", () => starting(created.id))
  send(created.id, "two", "web-two-2")
  await waitFor("the second message logged", async () => (await messages(created.id)).some((m) => m.id === "in:web:web-two-2"))
  rmSync(HOLD)
  await waitFor("both replies", () => replies(created.id).includes("Fixture reply: two"))
  expect(replies(created.id).filter((t) => t.startsWith("Fixture reply"))).toEqual(["Fixture reply: one", "Fixture reply: two"])
  expect(startsIn(created.workdir)).toBe(1)
}, 60_000)

test("a message to a dozing session is logged first, wakes it, and is answered", async () => {
  writeFileSync(HOLD, "")
  send(DOZING_ID, "wake up", "web-dozing-1")
  await waitFor("the starting state", () => starting(DOZING_ID))
  expect((await messages(DOZING_ID)).map((m) => m.id)).toContain("in:web:web-dozing-1")
  rmSync(HOLD)
  await waitFor("the reply", () => replies(DOZING_ID).includes("Fixture reply: wake up"))
  expect((await session(DOZING_ID)).status).toBe("active")
}, 60_000)

test("Stop while it starts: no reply, the session goes back to sleep, and the next message wakes it", async () => {
  writeFileSync(HOLD, "")
  const { created } = await launch({ name: "Stopped early", firstMessage: "never", client_message_id: "web-stop-1" })
  await waitFor("the agent's start", () => startsIn(created.workdir) === 1)
  expect((await api(`/sessions/${created.id}/interrupt`, { method: "POST" })).status).toBe(200)
  rmSync(HOLD)
  // What started is stopped and the row is asleep again (it was asleep from birth, so the log line is the evidence).
  await waitFor("put back to sleep", () => readFileSync(brokerLog, "utf8").split("\n").some((l) => l.includes("session_put_back_to_sleep") && l.includes(created.id)))
  expect((await session(created.id)).status).toBe("suspended")
  await waitFor("the status change back", () => statuses(created.id).at(-1) === "suspended")
  await Bun.sleep(500)
  expect(replies(created.id).filter((t) => t.startsWith("Fixture reply"))).toEqual([])
  expect((await messages(created.id)).map((m) => m.id)).toContain("in:web:web-stop-1")
  send(created.id, "again", "web-stop-2")
  await waitFor("the reply", () => replies(created.id).includes("Fixture reply: again"))
  expect(startsIn(created.workdir)).toBe(2)
}, 60_000)

test("spawn_session (an agent's door) still answers only once the new session is up", async () => {
  const before = replies(SEED_ID).length
  send(SEED_ID, `mcp-call mux-shim spawn_session ${JSON.stringify({ workdir: project, name: "Door child" })}`, "web-door-1")
  const reply = await waitFor("the tool's answer", () => replies(SEED_ID).slice(before).find((t) => t.startsWith("MCP")), 60_000)
  expect(reply).not.toContain("isError")
  const id = /"session_id":\s*"([^"]+)"/.exec(reply)?.[1] ?? /([0-9a-f-]{36})/.exec(reply)?.[1]
  expect(id).toBeTruthy()
  const child = await session(id!)
  expect(child.status).toBe("active")
  expect(startsIn(child.workdir)).toBe(1)
  send(id!, "hi child", "web-door-2")
  await waitFor("the child's reply", () => replies(id!).includes("Fixture reply: hi child"))
}, 90_000)

/** The JSON the seed session's tool call answered with ("MCP <json>"). */
async function callTool(tool: string, args: object, clientId: string): Promise<any> {
  const before = replies(SEED_ID).length
  send(SEED_ID, `mcp-call mux-shim ${tool} ${JSON.stringify(args)}`, clientId)
  const reply = await waitFor(`${tool}'s answer`, () => replies(SEED_ID).slice(before).find((t) => t.startsWith("MCP")), 60_000)
  expect(reply).not.toContain("isError")
  return JSON.parse(reply.slice("MCP ".length))
}

const answered = async (id: string, text: string) =>
  (await messages(id)).some((m) => m.direction === "outbound" && typeof m.text === "string" && m.text.includes(text))

test("delegate still answers once its worktree child is up, and the child gets its task through the inbox", async () => {
  const r = await callTool("delegate", { task: "child task one", agent: "claude", access: "bypass", checkout: "worktree", title: "Delegated child" }, "web-delegate-1")
  expect(r.session).toBeTruthy()
  // Started before the tool answered: its worktree is there and its agent was launched once in it.
  const starts = readFileSync(STARTS, "utf8").split("\n").filter((l) => l.includes("/worktrees/"))
  expect(starts.some((l) => existsSync(l) && execFileSync("git", ["-C", l, "branch", "--show-current"], { encoding: "utf8" }).trim() === "mux/delegated-child")).toBe(true)
  await waitFor("the child's answer to its task", () => answered(r.session, "child task one"))
}, 90_000)

test("council members still start and each gets the question", async () => {
  const r = await callTool("council", { question: "which is faster?", members: [{ agent: "claude", access: "bypass" }, { agent: "claude", access: "bypass" }], checkout: "shared" }, "web-council-1")
  expect(r.members).toHaveLength(2)
  for (const m of r.members) await waitFor(`member ${m.letter}'s answer`, () => answered(m.session, "which is faster?"), 60_000)
}, 120_000)

test("a chat message (Telegram's path) wakes a dozing session: the resume line, then the reply, in that chat", async () => {
  const body = JSON.stringify({ event: "message", payload: { id: "wa-in-1", chat_id: WA_JID, from: WA_JID, from_name: "Ahmet", body: "hello from the chat", timestamp: new Date().toISOString() } })
  const signature = `sha256=${createHmac("sha256", WA_SECRET).update(body).digest("hex")}`
  const res = await fetch(`http://127.0.0.1:${waPort}/`, { method: "POST", headers: { "X-Hub-Signature-256": signature, "content-type": "application/json" }, body })
  expect(res.status).toBe(200)
  await waitFor("the reply in the chat", () => sentToChat.some((m) => m.message.includes("Fixture reply: hello from the chat")), 60_000)
  expect(sentToChat[0]!.message).toBe(`Resuming session "chat-dozing"...`)
  expect((await messages(CHAT_DOZING_ID)).filter((m) => m.direction === "inbound").map((m) => m.text)).toEqual(["hello from the chat"])
  expect((await session(CHAT_DOZING_ID)).status).toBe("active")
}, 90_000)

test("a shared-checkout child whose start fails leaves its parent's worktree and branch alone", async () => {
  // A parent with a worktree of its own, up and answering.
  const { created: parent } = await launch({ name: "Parent with work", firstMessage: "hi parent", client_message_id: "web-parent-1" })
  await waitFor("the parent's reply", () => replies(parent.id).includes("Fixture reply: hi parent"))
  writeFileSync(join(parent.workdir, "uncommitted.txt"), "the parent's work")
  // It delegates in its own folder ("shared"), and the child's agent fails to start.
  writeFileSync(FAIL, "")
  const before = replies(parent.id).length
  send(parent.id, `mcp-call mux-shim delegate ${JSON.stringify({ task: "child task", agent: "claude", access: "bypass", checkout: "shared" })}`, "web-parent-2")
  const answer = await waitFor("the tool's answer", () => replies(parent.id).slice(before).find((t) => t.startsWith("MCP")), 60_000)
  expect(answer).toContain("isError")
  rmSync(FAIL, { force: true })
  // The parent's checkout, its work and its branch are all still there.
  expect(readFileSync(join(parent.workdir, "uncommitted.txt"), "utf8")).toBe("the parent's work")
  expect(execFileSync("git", ["-C", project, "branch", "--list", parent.session_branch], { encoding: "utf8" })).toContain(parent.session_branch)
  expect(execFileSync("git", ["-C", parent.workdir, "branch", "--show-current"], { encoding: "utf8" }).trim()).toBe(parent.session_branch)
}, 120_000)
