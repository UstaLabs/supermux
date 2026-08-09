import { test, expect, beforeEach } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { runCurator, type CuratorDeps } from "./run"
import { Registry } from "../session-manager/registry"
import { SessionManager, type SessionManagerPorts } from "../session-manager/manager"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"

let promptPath: string
let root: string

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "curator-"))
  promptPath = join(root, "prompt.md")
  writeFileSync(promptPath, "do the curation")
})

function makeDeps(over: Partial<CuratorDeps> = {}): { deps: CuratorDeps; calls: string[] } {
  const calls: string[] = []
  // Becomes active for a few polls once the prompt is delivered, then idle.
  let activePolls = 0
  const deps: CuratorDeps = {
    chatId: "web:test",
    repoPath: root,
    promptPath,
    spawn: async ({ name }) => {
      calls.push(`spawn:${name}`)
      return { name }
    },
    waitReady: async (name) => {
      calls.push(`ready:${name}`)
      return "sid-1"
    },
    sendInbound: async (sid, _content, chatId) => {
      calls.push(`inbound:${sid}:${chatId}`)
      activePolls = 4 // the agent picks up the task
    },
    isIdle: () => {
      if (activePolls > 0) {
        activePolls--
        return false
      }
      return true
    },
    getActive: () => "dockie",
    setActive: (chatId, name) => calls.push(`setActive:${chatId}:${name}`),
    archive: (name) => calls.push(`archive:${name}`),
    postNotice: async (_chatId, text) => {
      calls.push(`notice:${text.slice(0, 20)}`)
    },
    sleep: async () => {},
    ...over,
  }
  return { deps, calls }
}

test("happy path: spawn → route → deliver(once) → archive → restore active", async () => {
  const { deps, calls } = makeDeps()
  await runCurator(deps)
  expect(calls).toEqual([
    "spawn:nightly-curator",
    "ready:nightly-curator",
    "setActive:web:test:nightly-curator", // route replies to the chat
    "inbound:sid-1:web:test",
    "archive:nightly-curator",
    "setActive:web:test:dockie", // restore previous active session
  ])
})

test("delivery is retried until the agent goes active", async () => {
  let sends = 0
  let activePolls = 0
  const { deps, calls } = makeDeps({
    sendInbound: async () => {
      sends++
      if (sends >= 3) activePolls = 4 // first two sends are lost; third lands
    },
    isIdle: () => {
      if (activePolls > 0) {
        activePolls--
        return false
      }
      return true
    },
  })
  await runCurator(deps)
  expect(sends).toBe(3)
  expect(calls).toContain("archive:nightly-curator")
})

test("never goes active → error notice, still archives + restores", async () => {
  const { deps, calls } = makeDeps({
    isIdle: () => true, // never picks up
    sendInbound: async () => {},
  })
  await runCurator({ ...deps, sleep: async () => {} })
  expect(calls.some((c) => c.startsWith("notice:"))).toBe(true)
  expect(calls).toContain("archive:nightly-curator")
  expect(calls).toContain("setActive:web:test:dockie")
})

test("spawn that never becomes ready → error notice + no inbound, no crash", async () => {
  const { deps, calls } = makeDeps({ waitReady: async () => undefined })
  await runCurator(deps)
  expect(calls.some((c) => c.startsWith("inbound:"))).toBe(false)
  expect(calls.some((c) => c.startsWith("notice:"))).toBe(true)
  expect(calls).toContain("setActive:web:test:dockie")
})

// Regression pin for the Aug-2026 nightly failure: the curator agent was
// switched to codex while main.ts still delivered over the claude shim socket
// (inbound_queued → inbound_undeliverable → curator_run_error) and waited on
// the claude `connected` flag. With the SessionManager funnel, a codex-kind
// curator must (a) become ready via ADAPTER PRESENCE — `connected` stays false
// here — and (b) receive the prompt through adapter.send, never the socket.
test("codex-kind curator: prompt lands via the adapter funnel, readiness is adapter presence", async () => {
  const registry = new Registry() // in-memory test db
  const socketSends: string[] = []
  // deliver()/isDeliverable() only touch registry, runtimes, and these two
  // ports — the rest of the bag is never crossed in this test.
  const manager = new SessionManager(registry, {
    socket: { sendInbound: async (id: string) => { socketSends.push(id) } },
    inbound: {},
  } as unknown as SessionManagerPorts)
  const row = registry.register({ name: "nightly-curator", workdir: root, pid: 0, agent: "codex", connected: false })
  const sent: Array<{ text: string; meta: any }> = []
  // The adapter registers a beat AFTER waitReady starts (codex app-server spin-up).
  setTimeout(() => {
    manager.registerRuntime(row.id, {
      kind: "codex",
      adapter: { send: async (text: string, meta: any) => { sent.push({ text, meta }) } } as unknown as CodexAdapter,
      handle: {} as CodexSpawnHandle,
    })
  }, 20)
  let activePolls = 0
  const deps: CuratorDeps = {
    chatId: "web:test",
    repoPath: root,
    promptPath,
    spawn: async ({ name }) => ({ name }),
    // Mirrors the main.ts wiring: kind-aware readiness through the SessionManager.
    waitReady: async (name) => {
      const s = registry.resolveName(name)
      if (!s) return undefined
      return (await manager.waitDeliverable(s.id, 2_000, 5)) ? s.id : undefined
    },
    sendInbound: async (sid, content, cid) => {
      const r = await manager.deliver(sid, content, { chat_id: cid })
      if (r.ok) activePolls = 4 // the agent picks up the task
    },
    isIdle: () => {
      if (activePolls > 0) {
        activePolls--
        return false
      }
      return true
    },
    getActive: () => undefined,
    setActive: () => {},
    archive: () => {},
    postNotice: async () => {},
    sleep: async () => {},
  }
  await runCurator(deps)
  expect(sent).toEqual([{ text: "do the curation", meta: { chat_id: "web:test" } }])
  expect(socketSends).toEqual([]) // the claude shim socket is never touched
})

test("re-entrancy: a second concurrent run is skipped", async () => {
  let release: () => void
  const gate = new Promise<void>((r) => (release = r))
  const { deps, calls } = makeDeps({
    spawn: async ({ name }) => {
      calls.push(`spawn:${name}`)
      await gate
      return { name }
    },
  })
  const first = runCurator(deps)
  const second = runCurator(deps) // should no-op while first holds the lock
  await second
  release!()
  await first
  expect(calls.filter((c) => c.startsWith("spawn:")).length).toBe(1)
})
