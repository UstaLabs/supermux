import { afterAll, beforeAll, beforeEach, expect, test } from "bun:test"
import { createServer, type Server } from "node:net"
import { mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { encodeFrame, decodeFrames } from "../shared/frame-codec"
import { localEndpoint } from "../core/local-endpoint"
import { runAgentCli, helpText } from "./agent"

// A fake broker on a session socket: records every op and answers from `reply`.
const SESSION = "11111111-2222-4333-8444-555555555555"
let dir: string
let server: Server
let calls: Array<{ kind: string; name: string; args: Record<string, unknown> }> = []
let reply: (name: string, args: any) => { ok: boolean; value?: unknown; error?: string } = () => ({ ok: true })

const SESSIONS = [
  { id: SESSION, name: "Me", agent: "claude", model: "opus", workdir: "/w", mute: false, state: "working", children: [] },
  { id: "3f9c2d1e-7a4b-4c55-9e21-5d0b8a6f4c12", name: "Git cleanup", agent: "codex", workdir: "/r", mute: false, state: "idle", children: [] },
  { id: "c4d5e6f7-1a2b-4c3d-8e9f-0a1b2c3d4e5f", name: "Release train", agent: "claude", model: "opus", workdir: "/r", mute: false, state: "suspended", children: [] },
]

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "sm-cli-"))
  server = createServer((sock) => {
    let buf: Buffer = Buffer.alloc(0)
    sock.on("data", (c: Buffer) => {
      buf = Buffer.concat([buf, c])
      const { messages, rest } = decodeFrames(buf)
      buf = rest
      for (const m of messages as any[]) {
        calls.push({ kind: m.kind, name: m.op.name, args: m.op.args })
        sock.write(encodeFrame({ kind: "result", call_id: m.call_id, ...reply(m.op.name, m.op.args) }))
      }
    })
  })
  await new Promise<void>((r) => server.listen(localEndpoint(SESSION, { socketsDir: dir }), () => r()))
})
afterAll(() => { server.close(); rmSync(dir, { recursive: true, force: true }) })
beforeEach(() => {
  calls = []
  reply = (name) => (name === "list_sessions" ? { ok: true, value: SESSIONS } : { ok: true })
})

async function run(argv: string[], opts: { env?: Record<string, string | undefined>; stdin?: string } = {}) {
  const out: string[] = [], err: string[] = []
  const code = await runAgentCli(argv, {
    env: opts.env ?? { MUX_SESSION_ID: SESSION, MUX_SOCKETS_DIR: dir }, cwd: dir,
    out: (s) => out.push(s), err: (s) => err.push(s), stdin: () => opts.stdin ?? "", timeoutMs: 2000,
  })
  return { code, out: out.join("\n"), err: err.join("\n") }
}

test("help is the whole reference on one page: every command, and no pointer to another page but card's rules", async () => {
  const r = await run(["help"])
  expect(r.code).toBe(0)
  for (const cmd of ["session ls", "session find", "session read", "session spawn", "msg", "delegate", "council start", "card show", "attach", "file get", "port ls", "port expose", "memory search", "api"]) {
    expect(r.out).toContain(`supermux ${cmd}`)
  }
  expect(r.out).toContain("never wait or poll")
  expect(r.out).not.toContain("supermux docs")
  expect((await run(["--help"])).out).toBe(r.out)
})

test("a command's --help is just its lines and an example", async () => {
  const r = await run(["session", "read", "--help"])
  expect(r.out.split("\n")).toEqual([expect.stringContaining("supermux session read SESSION"), "Example:", "  supermux session read a1b2c3d4 --grep fix"])
  expect(r.out).not.toContain("session spawn")
  expect(helpText("card")).toContain("var(--sm-chart-1..5)")
})

test("session ls: a table by default, every field on every JSON row, archived hidden unless --all", async () => {
  reply = (name) => ({ ok: true, value: name === "list_sessions" ? [...SESSIONS, { id: "a1b2c3d4-0000-4000-8000-000000000000", name: "Old", agent: "claude", workdir: "/r", state: "archived" }] : undefined })
  const t = await run(["session", "ls"])
  expect(t.out).toContain("MODEL")
  expect(t.out).not.toContain("Old")
  const j = JSON.parse((await run(["session", "ls", "--json"])).out)
  expect(j).toHaveLength(3)
  expect(j[1]).toEqual({ id: SESSIONS[1]!.id, name: "Git cleanup", agent: "codex", model: null, state: "idle", workdir: "/r", mute: false, parent: null, children: [], council: null })
  expect(JSON.parse((await run(["session", "ls", "--all", "--json", "id,name"])).out)).toHaveLength(4)
  expect(JSON.parse((await run(["session", "ls", "--state", "idle", "--json", "name"])).out)).toEqual([{ name: "Git cleanup" }])
})

test("msg resolves names and id prefixes, sends one message_session, and says replies come to the inbox", async () => {
  const r = await run(["msg", "git cleanup", "c4d5e6f7", "done testing"])
  expect(r.code).toBe(0)
  const sent = calls.find((c) => c.name === "message_session")!
  expect(sent.args).toEqual({ to: [SESSIONS[1]!.id, SESSIONS[2]!.id], text: "done testing", interrupt: false })
  expect(r.out).toContain("no need to wait")
})

test("an unknown session is exit 3; a missing argument is a usage error, exit 2", async () => {
  const r = await run(["msg", "Nobody", "hi"])
  expect(r.code).toBe(3)
  expect(r.err).toContain("no session 'Nobody'")
  expect(calls.some((c) => c.name === "message_session")).toBe(false)
  const u = await run(["msg", "hi"])
  expect(u.code).toBe(2)
  expect(u.err).toContain("usage: supermux msg")
})

test("delegate and council say in their output that they returned and answers arrive in the inbox", async () => {
  reply = (name) => ({ ok: true, value: name === "delegate" ? { session_id: "x", name: "Review" } : { council: "c1" } })
  const d = await run(["delegate", "review src/cli.ts", "--agent", "claude", "--access", "dont-ask"])
  expect(d.out).toContain("don't wait, poll or sleep")
  expect(calls[0]).toMatchObject({ name: "delegate", args: { task: "review src/cli.ts", agent: "claude", access: "dont-ask", checkout: "shared" } })
  const c = await run(["council", "start", "Ship it?", "--member", "claude:dont-ask", "--member", "codex:read-only:gpt-5"])
  expect(c.out).toContain("don't wait, poll or sleep")
  expect(calls[1]!.args).toEqual({ question: "Ship it?", members: [{ agent: "claude", access: "dont-ask" }, { agent: "codex", access: "read-only", model: "gpt-5" }], checkout: "shared" })
})

test("card show reads the page itself (any path) and sends its HTML; a missing file says so, exit 3", async () => {
  const page = join(mkdtempSync(join(tmpdir(), "sm-page-")), "chart.html")
  writeFileSync(page, "<svg></svg>")
  reply = () => ({ ok: true, value: "shown" })
  const r = await run(["card", "show", page, "--title", "Rides"])
  expect(r.code).toBe(0)
  expect(calls[0]).toEqual({ kind: "outbound", name: "show_html", args: { title: "Rides", html: "<svg></svg>" } })
  const missing = await run(["card", "show", "/nope/x.html", "--title", "T"])
  expect(missing.code).toBe(3)
  expect(missing.err).toContain("no such file")
  reply = () => ({ ok: true, value: "not shown — fix the page and call show_html again:\n- the page is blank at 390 px" })
  const bad = await run(["card", "show", page, "--title", "Rides"])
  expect(bad.code).toBe(1)
  expect(bad.out).toContain("NOT shown")
  expect(bad.out).toContain("- the page is blank at 390 px")
  reply = () => ({ ok: true, value: "shown" })
  expect(JSON.parse((await run(["card", "show", page, "--title", "Rides", "--json"])).out)).toEqual({ shown: true, problems: [], text: "shown" })
})

test("attach sends absolute paths (relative to the shell's folder)", async () => {
  await run(["attach", "out/home.png", "-m", "the home screen"])
  expect(calls[0]).toEqual({ kind: "outbound", name: "attach", args: { files: [join(dir, "out/home.png")], text: "the home screen" } })
})

test("port ls lists links through list_proxies", async () => {
  reply = () => ({ ok: true, value: [{ domain: "fold-preview", port: 4173, isPublic: true, sessionName: "Me", url: "https://x/p/fold-preview/" }] })
  const j = JSON.parse((await run(["port", "ls", "--json"])).out)
  expect(calls[0]!.name).toBe("list_proxies")
  expect(j).toEqual([{ name: "fold-preview", port: 4173, public: true, url: "https://x/p/fold-preview/", session: "Me" }])
})

test("--json takes a field list, never a query word", async () => {
  reply = () => ({ ok: true, value: [] })
  await run(["memory", "search", "--json", "codex", "limit"])
  expect(calls[0]!.args).toEqual({ query: "codex limit" })
})

test("broker refusals map to exit codes; JSON errors carry a code", async () => {
  reply = () => ({ ok: false, error: "can only update your own proxies" })
  const r = await run(["port", "private", "demo", "--json"])
  expect(r.code).toBe(4)
  expect(JSON.parse(r.err)).toEqual({ error: { code: "denied", message: "can only update your own proxies" } })
  reply = () => ({ ok: false, error: "port must be 1-65535" })
  expect((await run(["port", "expose", "70000"])).code).toBe(1)
})

test("with the broker gone it is exit 5", async () => {
  const gone = await run(["session", "ls"], { env: { MUX_SESSION_ID: "99999999-0000-4000-8000-000000000000", MUX_SOCKETS_DIR: dir } })
  expect(gone.code).toBe(5)
})

test("api with no args lists the ops; an op with required params prints them instead of failing", async () => {
  const list = await run(["api"])
  expect(list.out.split("\n")).toContain("spawn_session")
  const spawn = await run(["api", "spawn_session"])
  expect(spawn.out).toContain("-f workdir=string  (required)")
  expect(calls).toHaveLength(0)
})

// #117: `supermux applet …`
test("applet: help points to the one long page; the page has the rules, an example per verb and the bench fixes", async () => {
  expect((await run(["help"])).out).toContain("'applet --help' has the rules")
  const page = (await run(["applet", "--help"])).out
  expect(page).toBe(helpText("applet"))
  for (const v of ["list", "info APPLET", "new APPLET", "restart APPLET", "logs APPLET", "run APPLET JOB", "backup APPLET", "APPLET CMD"]) expect(page).toContain(`supermux applet ${v}`)
  for (const ex of ["applet new job-tracker", "applet restart job-tracker", "applet logs job-tracker --job", "applet run job-tracker gmail", "applet backup job-tracker -m"]) expect(page).toContain(ex)
  expect(page).toContain("copy back only what is missing")
  expect(page).toContain("never overwrite live data")
  expect(page).toContain("NOT a project's product app")
  expect(page).toContain("keeps running after the command")
  expect((await run(["applet"])).out).toBe(page)
})

test("applet new resolves --from against the agent's cwd and prints the folder and link", async () => {
  reply = (name, args) => name === "applet_new" ? { ok: true, value: { name: args.applet, title: "Job tracker", folder: "/h/.mux/applets/job-tracker", url: "https://r/a/job-tracker/", state: "static", error: null } } : { ok: true }
  const r = await run(["applet", "new", "job-tracker", "--from", "src/app", "--title", "Job tracker"])
  expect(r.code).toBe(0)
  expect(calls).toEqual([{ kind: "orchestration", name: "applet_new", args: { applet: "job-tracker", from: join(dir, "src/app"), title: "Job tracker" } }])
  expect(r.out).toContain("folder: /h/.mux/applets/job-tracker")
  expect(r.out).toContain("link:   https://r/a/job-tracker/")
})

test("applet run prints the job's real output and exits with its result", async () => {
  reply = (name) => name === "applet_run" ? { ok: true, value: { name: "t", job: "sync", exit_code: 2, output: "synced 3 rows\nerror: quota\n" } } : { ok: true }
  const r = await run(["applet", "run", "t", "sync"])
  expect(r.out).toBe("synced 3 rows\nerror: quota\n--- t/sync exited 2")
  expect(r.code).toBe(1)
  expect((await run(["applet", "run", "t"])).code).toBe(2)
})

test("applet restart says the server keeps running, or that it did not stay up", async () => {
  reply = () => ({ ok: true, value: { name: "t", server: true, state: "crashed", error: "the server exited (code 1)", logs: "boom", note: "the server did not stay up; fix it" } })
  const bad = await run(["applet", "restart", "t"])
  expect(bad.code).toBe(1)
  expect(bad.out).toContain("NOT running: t (the server exited (code 1))")
  expect(bad.out).toContain("boom")
})

test("applet list: every row the same shape in --json; owner-only verbs exit 4, unknown applets 3", async () => {
  reply = (name) => name === "applet_list" ? { ok: true, value: [{ name: "a", title: "A", state: "running", url: "u", needs_you: [] }] }
    : name === "applet_delete" ? { ok: false, error: "not allowed: only the owner can delete an applet" }
    : name === "applet_info" ? { ok: false, error: "not found: no applet named \"zz\"" } : { ok: true }
  const j = await run(["applet", "list", "--json"])
  expect(JSON.parse(j.out)).toEqual([{ name: "a", title: "A", state: "running", url: "u", needs_you: [], error: null, updated_by: null, updated_at: null }])
  expect((await run(["applet", "delete", "a"])).code).toBe(4)
  expect((await run(["applet", "zz", "list-cards"])).code).toBe(3)
})

test("outside any session (no MUX_SESSION_ID) it talks to the operator socket in the sockets dir (#156)", async () => {
  const seen: string[] = []
  const op = createServer((sock) => {
    let buf: Buffer = Buffer.alloc(0)
    sock.on("data", (c: Buffer) => { buf = Buffer.concat([buf, c]); for (const m of decodeFrames(buf).messages as any[]) { seen.push(m.op.name); sock.write(encodeFrame({ kind: "result", call_id: m.call_id, ok: true, value: SESSIONS })) } })
  })
  await new Promise<void>((r) => op.listen(localEndpoint("operator", { socketsDir: dir }), () => r()))
  try {
    const r = await run(["session", "ls", "--json", "name"], { env: { MUX_SOCKETS_DIR: dir } })
    expect(r.code).toBe(0)
    expect(seen).toEqual(["list_sessions"])
    expect(calls).toHaveLength(0)   // the session socket was not used
  } finally { op.close() }
})
