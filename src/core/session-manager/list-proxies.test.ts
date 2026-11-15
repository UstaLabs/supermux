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
