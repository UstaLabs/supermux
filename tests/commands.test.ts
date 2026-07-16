import { test, expect, beforeEach, afterEach } from "bun:test"
import { handleSlash, CommandCtx } from "../src/core/commands"
import { Registry } from "../src/core/session-manager/registry"
import { MessageStore } from "../src/core/session-manager/messages"
import { openDb, runMigrations } from "../src/core/storage/db"
import type { Database } from "bun:sqlite"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

let r: Registry
let messageLog: MessageStore
let ctx: CommandCtx
let spawned: any[] = []
let killed: string[] = []
let menuRefreshed = 0
let tmpDir: string
let db: Database
let zoomId: string
let anaId: string

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-cmd-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  r = new Registry(db)
  messageLog = new MessageStore(db)
  const ana = r.register({ name: "ana",  workdir: "/h", tmux_target: "mux:ana",  pid: 1, role: "personal_assistant", is_default: true })
  anaId = ana.id
  const zoom = r.register({ name: "zoom", workdir: "/z", tmux_target: "mux:zoom", pid: 2 })
  zoomId = zoom.id
  spawned = []; killed = []; menuRefreshed = 0
  ctx = {
    registry: r,
    messageLog,
    chat_id: "chat-1",
    fromSession: "ana",  // who's calling the orchestration (n/a for chat-initiated)
    spawnSession: async (workdir, name, agent, model) => { spawned.push({ workdir, name, agent, model }); return { name: name ?? "n", session_id: "s" } },
    killSession:  async (name) => { killed.push(name) },
    refreshMenu:  async () => { menuRefreshed++ },
  }
})

afterEach(() => {
  db.close()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("/sessions lists registry", async () => {
  const r1 = await handleSlash({ command: "sessions", rest: "" }, ctx)
  expect(r1.text).toContain("ana")
  expect(r1.text).toContain("zoom")
})

test("/switch <name> sets active", async () => {
  await handleSlash({ command: "switch", rest: "zoom" }, ctx)
  expect(r.getActive("chat-1")).toBe(zoomId)
})

test("/switch <unknown> reports error", async () => {
  const r1 = await handleSlash({ command: "switch", rest: "nope" }, ctx)
  expect(r1.text).toMatch(/no such session/i)
})

test("/active shows current", async () => {
  r.setActive("chat-1", zoomId)
  const r1 = await handleSlash({ command: "active", rest: "" }, ctx)
  expect(r1.text).toContain("zoom")
})

test("/spawn <workdir> calls spawnSession and refreshes menu", async () => {
  await handleSlash({ command: "spawn", rest: "/tmp/foo" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: undefined, agent: "claude", model: undefined }])
  expect(menuRefreshed).toBe(1)
})

test("/spawn <workdir> as <name>", async () => {
  await handleSlash({ command: "spawn", rest: "/tmp/foo as bar" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: "bar", agent: "claude", model: undefined }])
})

test("/spawn --agent codex passes agent kind", async () => {
  await handleSlash({ command: "spawn", rest: "/tmp/foo --agent codex" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: undefined, agent: "codex", model: undefined }])
})

test("/spawn_codex routes through cmdSpawn with --agent codex appended", async () => {
  await handleSlash({ command: "spawn_codex", rest: "/tmp/foo" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: undefined, agent: "codex", model: undefined }])
  expect(menuRefreshed).toBe(1)
})

test("/spawn_cursor routes through cmdSpawn with --agent cursor appended", async () => {
  await handleSlash({ command: "spawn_cursor", rest: "/tmp/bar" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/bar", name: undefined, agent: "cursor", model: undefined }])
  expect(menuRefreshed).toBe(1)
})

test("/spawn --agent unknown returns error", async () => {
  const r1 = await handleSlash({ command: "spawn", rest: "/tmp/foo --agent blorp" }, ctx)
  expect(r1.text).toMatch(/unknown agent/i)
  expect(spawned).toEqual([])
})

test("/kill <name> without yes asks for confirmation", async () => {
  const r1 = await handleSlash({ command: "kill", rest: "zoom" }, ctx)
  expect(r1.text).toMatch(/confirm/i)
  expect(killed).toEqual([])
})

test("/kill <name> yes kills + refreshes + applies fallback", async () => {
  r.setActive("chat-1", zoomId)
  await handleSlash({ command: "kill", rest: "zoom yes" }, ctx)
  expect(killed).toEqual([zoomId])
  expect(r.get(zoomId)).toBeUndefined()
  expect(r.getActive("chat-1")).toBe(anaId)
  expect(menuRefreshed).toBe(1)
})

test("/rename old new", async () => {
  await handleSlash({ command: "rename", rest: "zoom zumzum" }, ctx)
  expect(r.resolveName("zumzum")).toBeDefined()
  expect(menuRefreshed).toBe(1)
})

test("/mute <name>", async () => {
  await handleSlash({ command: "mute", rest: "zoom" }, ctx)
  expect(r.get(zoomId)?.mute).toBe(true)
})

test("/grant_orchestrate zoom flips can_orchestrate", async () => {
  await handleSlash({ command: "grant_orchestrate", rest: "zoom" }, ctx)
  expect(r.get(zoomId)?.can_orchestrate).toBe(true)
})

test("unknown command returns help-ish error", async () => {
  const r1 = await handleSlash({ command: "wtf", rest: "" }, ctx)
  expect(r1.text).toMatch(/unknown command/i)
})

test("/usage returns formatted usage text", async () => {
  const r1 = await handleSlash({ command: "usage", rest: "" }, ctx)
  expect(typeof r1.text).toBe("string")
  expect(r1.text.length).toBeGreaterThan(0)
})

test("/show prints recent log entries", async () => {
  const anaSession = r.get(anaId)!
  messageLog.append(anaSession.id, { id: "1", ts: "t", direction: "outbound", channel: "telegram", chat_id: "telegram:1", op: "reply", text: "hello" })
  messageLog.append(anaSession.id, { id: "2", ts: "t", direction: "inbound",  channel: "telegram", chat_id: "telegram:1", text: "world" })
  const result = await handleSlash({ command: "show", rest: "ana 10" }, ctx)
  expect(result.text).toContain("hello")
  expect(result.text).toContain("world")
})

test("/spawn --model sonnet passes model to spawnSession", async () => {
  await handleSlash({ command: "spawn", rest: "/tmp/foo --model sonnet" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: undefined, agent: "claude", model: "sonnet" }])
})

test("/spawn --model opus --agent codex passes both", async () => {
  await handleSlash({ command: "spawn", rest: "/tmp/foo --model opus --agent codex" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/foo", name: undefined, agent: "codex", model: "opus" }])
})

test("/spawn_cursor --model auto passes model", async () => {
  await handleSlash({ command: "spawn_cursor", rest: "/tmp/bar --model auto" }, ctx)
  expect(spawned).toEqual([{ workdir: "/tmp/bar", name: undefined, agent: "cursor", model: "auto" }])
})

test("/model with no args shows current model for active session", async () => {
  r.setActive("chat-1", anaId)
  const result = await handleSlash({ command: "model", rest: "" }, ctx)
  expect(result.text).toContain("ana")
  expect(result.text).toContain("[claude]")
})

test("/model <name> switches active session model", async () => {
  r.setActive("chat-1", anaId)
  const result = await handleSlash({ command: "model", rest: "sonnet" }, ctx)
  expect(result.text).toContain("sonnet")
  expect(r.get(anaId)?.model).toBe("sonnet")
})

test("/model <session> <name> switches specific session model", async () => {
  const result = await handleSlash({ command: "model", rest: "zoom opus" }, ctx)
  expect(result.text).toContain("zoom")
  expect(result.text).toContain("opus")
  expect(r.get(zoomId)?.model).toBe("opus")
})

test("/model with no active session returns error", async () => {
  const r2 = new Registry()
  const ctx2 = { ...ctx, registry: r2, chat_id: "no-active" }
  const result = await handleSlash({ command: "model", rest: "sonnet" }, ctx2)
  expect(result.text).toMatch(/no active session/i)
})

test("/sessions shows agent:model tag when model is set", async () => {
  r.get(anaId)!.model = "opus-4-7"
  const result = await handleSlash({ command: "sessions", rest: "" }, ctx)
  expect(result.text).toContain("[claude:opus-4-7]")
})

test("/sessions shows agent-only tag when no model", async () => {
  const result = await handleSlash({ command: "sessions", rest: "" }, ctx)
  expect(result.text).toContain("[claude]")
})

test("/kill allows removing the last personal assistant", async () => {
  const res = await handleSlash({ command: "kill", rest: "ana yes" }, ctx)
  expect(res.text).toBe("killed ana")
  expect(killed).toEqual([anaId])
  expect(r.listPAs()).toHaveLength(0)
})

test("/kill a non-last PA reassigns is_default to the oldest remaining PA", async () => {
  r.register({ name: "bob", workdir: "/h2", tmux_target: "t", pid: 3, role: "personal_assistant" })
  const res = await handleSlash({ command: "kill", rest: "ana yes" }, ctx)
  expect(killed).toEqual([anaId])
  expect(r.defaultPA()?.name).toBe("bob")
})

import { describe } from "bun:test"

describe("proxy commands", () => {
  test("/proxies lists all proxies with domain, port, and session", async () => {
    r.addProxy({ domain: "myapp", sessionId: zoomId, port: 3000 })
    const ctxWithBase = { ...ctx, proxyBaseDomain: "example.com" }
    const result = await handleSlash({ command: "proxies", rest: "" }, ctxWithBase)
    expect(result.text).toContain("https://myapp.example.com")
    expect(result.text).toContain("3000")
    expect(result.text).toContain("zoom")
  })

  test("/proxies shows empty state when no proxies registered", async () => {
    const result = await handleSlash({ command: "proxies", rest: "" }, ctx)
    expect(result.text).toBe("no active proxies")
  })

  test("/proxies in path mode uses publicUrl", async () => {
    r.addProxy({ domain: "myapp", sessionId: zoomId, port: 3000 })
    const ctxPathMode = { ...ctx, proxyPublicUrl: "https://broker.example.com" }
    const result = await handleSlash({ command: "proxies", rest: "" }, ctxPathMode)
    expect(result.text).toContain("https://broker.example.com/p/myapp/")
    expect(result.text).toContain("3000")
    expect(result.text).toContain("zoom")
  })

  test("/proxy s1 3000 myapp creates a proxy with explicit domain", async () => {
    const ctxWithBase = { ...ctx, proxyBaseDomain: "example.com" }
    const result = await handleSlash({ command: "proxy", rest: "zoom 3000 myapp" }, ctxWithBase)
    expect(result.text).toContain("proxy created")
    expect(result.text).toContain("https://myapp.example.com")
    expect(result.text).toContain("3000")
    expect(r.getProxy("myapp")).toBeDefined()
    expect(r.getProxy("myapp")?.sessionId).toBe(zoomId)
    expect(r.getProxy("myapp")?.port).toBe(3000)
  })

  test("/proxy s1 3000 myapp in path mode uses publicUrl", async () => {
    const ctxPathMode = { ...ctx, proxyPublicUrl: "https://broker.example.com" }
    const result = await handleSlash({ command: "proxy", rest: "zoom 3000 myapp" }, ctxPathMode)
    expect(result.text).toContain("proxy created")
    expect(result.text).toContain("https://broker.example.com/p/myapp/")
    expect(result.text).toContain("3000")
  })

  test("/proxy s1 3000 (no domain) creates with random px- domain", async () => {
    const result = await handleSlash({ command: "proxy", rest: "zoom 3000" }, ctx)
    expect(result.text).toContain("proxy created")
    expect(result.text).toMatch(/px-[0-9a-f]{8}/)
    const proxies = r.listProxies()
    expect(proxies.length).toBe(1)
    expect(proxies[0]?.domain).toMatch(/^px-[0-9a-f]{8}$/)
  })

  test("/proxy nope 3000 rejects unknown session", async () => {
    const result = await handleSlash({ command: "proxy", rest: "nope 3000 myapp" }, ctx)
    expect(result.text).toMatch(/no such session/)
    expect(result.text).toContain("nope")
  })

  test("/unproxy myapp removes a proxy", async () => {
    r.addProxy({ domain: "myapp", sessionId: zoomId, port: 3000 })
    const result = await handleSlash({ command: "unproxy", rest: "myapp" }, ctx)
    expect(result.text).toContain("removed proxy")
    expect(result.text).toContain("myapp")
    expect(r.getProxy("myapp")).toBeUndefined()
  })

  test("/unproxy nope rejects unknown domain", async () => {
    const result = await handleSlash({ command: "unproxy", rest: "nope" }, ctx)
    expect(result.text).toMatch(/no proxy registered/)
    expect(result.text).toContain("nope")
  })
})
