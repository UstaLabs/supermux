// #156: `list_proxies`, the op behind the CLI's `supermux port ls` (no MCP tool lists links).
import { expect, test } from "bun:test"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { SessionManager } from "./manager"
import { fakePorts } from "../../../tests/helpers/session-manager-ports"

test("list_proxies lists every link with its public flag, owner and url, for any session", async () => {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  const ports = fakePorts(db, { frames: [] })
  ports.orchestration.exposedProxyLinksBaseUrl = () => "https://box.example"
  const registry = new Registry(db)
  const m = new SessionManager(registry, ports)
  const me = registry.register({ name: "worker", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
  const other = registry.register({ name: "other", workdir: "/tmp", pid: 0, agent: "claude", connected: false })
  registry.addProxy({ domain: "fold-preview", sessionId: other.id, port: 4173, isPublic: true })
  registry.addProxy({ domain: "mine", sessionId: me.id, port: 5173 })
  const r = await m.handleOrchestration({ kind: "orchestration", call_id: "c1", session_id: me.id, op: { name: "list_proxies", args: {} } } as any)
  expect(r.ok).toBe(true)
  const rows = (r.value as any[]).map(({ createdAt: _, ...p }) => p).sort((a, b) => a.domain.localeCompare(b.domain))
  expect(rows).toEqual([
    { domain: "fold-preview", port: 4173, isPublic: true, sessionName: "other", mine: false, url: "https://box.example/p/fold-preview/" },
    { domain: "mine", port: 5173, isPublic: false, sessionName: "worker", mine: true, url: "https://box.example/p/mine/" },
  ])
})

test("reads are never served from a settled call, and CLI calls are never deduplicated (#156)", async () => {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../storage/migrations"))
  const registry = new Registry(db)
  const m = new SessionManager(registry, fakePorts(db, { frames: [] }))
  const me = registry.register({ name: "worker", workdir: "/tmp", pid: 0, agent: "codex", connected: false })
  const ls = (call_id: string) => m.handleOrchestration({ kind: "orchestration", call_id, session_id: me.id, op: { name: "list_proxies", args: {} } } as any)
  expect(((await ls("mcp-1")).value as any[])).toHaveLength(0)
  registry.addProxy({ domain: "demo", sessionId: me.id, port: 5173 })
  // the MCP path, same op and args, right after: fresh
  expect(((await ls("mcp-2")).value as any[]).map((p) => p.isPublic)).toEqual([false])
  // a mutation from the CLI, then the same mutation again: both run (the second is a real second call)
  const priv = (call_id: string, pub: boolean) => m.handleOrchestration({ kind: "orchestration", call_id, session_id: me.id, op: { name: "set_proxy_public", args: { domain: "demo", public: pub } } } as any)
  await priv("cli-1", true)
  await priv("cli-2", false)
  await priv("cli-3", true)
  expect(((await ls("cli-4")).value as any[]).map((p) => p.isPublic)).toEqual([true])
})
