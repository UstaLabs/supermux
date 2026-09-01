import { test, expect } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execSync } from "child_process"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { Registry } from "../session-manager/registry"
import { SessionManager, type SessionManagerPorts } from "../session-manager/manager"
import { ReviewStore } from "../review/store"
import { WalkthroughStore } from "./store"
import type { FileStore } from "../files/store"

function git(cwd: string, cmd: string) {
  execSync(`git ${cmd}`, { cwd, encoding: "utf-8" })
}

function ports(db: ReturnType<typeof openDb>, frames: object[], cards: string[]): SessionManagerPorts {
  const reviewStore = new ReviewStore(db)
  const walkthroughStore = new WalkthroughStore(db)
  return {
    getWebChannel: () => ({ broadcastToAll: (f: object) => { frames.push(f) } }),
    getAgentRpc: () => ({ settle: () => {}, fail: () => {} }),
    socket: { sendInbound: async () => {} },
    inbound: {},
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    cleanup: {
      terminals: { killAllForSession: async () => {} },
      fsWatcher: { killSession: () => {} },
      stopClaudeTailer: () => {},
      releaseDraftAttachments: () => {},
      syncGitStatus: () => {},
    },
    displays: {
      killAllForSession: async () => {},
      start: async () => { throw new Error("unused") },
      get: () => undefined,
      stop: async () => {},
    },
    agentState: { applyEvent: () => {}, clear: () => {}, get: () => ({ phase: "idle", since: 0 }) },
    bgTasks: { clear: () => {} },
    commands: { remove: () => {}, refresh: async () => {} },
    config: { lookupModels: () => [] },
    register: {
      interruptClaudePane: async () => {},
      notifyAgentError: async () => {},
      ensureClaudeTailer: () => {},
      maybeAutoSendSoulSetup: async () => {},
    },
    outbound: {
      onAssistantMessage: async () => ({ ok: true as const, delivered: 1 }),
      getChannel: () => undefined,
      telegramApi: undefined,
    },
    orchestration: {
      spawnSession: async () => { throw new Error("unused") },
      refreshTelegramMenu: async () => {},
      wsDto: () => undefined,
      exposedProxyLinksBaseUrl: () => undefined,
      proxyWsPayload: () => ({}),
      proxyLiveness: { getStatus: () => "unknown", refresh: async () => {} },
      postBrokerInbound: (_id, text) => { cards.push(text) },
    },
    stores: {
      fileStore: {} as unknown as FileStore,
      messageLog: { get: () => [], update: () => false, addReaction: () => false, findByChannelMessageId: () => undefined },
      searchStore: { searchKnowledge: () => [], searchSessions: () => [] },
      db,
      reviewStore,
      walkthroughStore,
    },
    resume: {
      bind: async () => {},
      ensureSessionWorktree: async () => {},
      sessionEffort: () => undefined,
      resolveAttachment: async () => { throw new Error("unused") },
      wireAdapterEvents: () => {},
      sessionBackend: { list: async () => [], create: async () => { throw new Error("unused") } } as never,
      tmuxSession: "mux-test",
    },
  }
}

test("walkthrough tool validates steps, broadcasts, and posts a ready card", async () => {
  const dir = mkdtempSync(join(tmpdir(), "wt-ops-"))
  git(dir, "init -q")
  git(dir, "config user.email t@t.com")
  git(dir, "config user.name t")
  writeFileSync(join(dir, "a.ts"), "one\n")
  git(dir, "add -A")
  git(dir, "commit -q -m init")
  writeFileSync(join(dir, "a.ts"), "one\ntwo\n")
  try {
    const db = openDb(":memory:")
    runMigrations(db, MIGRATIONS)
    const frames: object[] = []
    const cards: string[] = []
    const m = new SessionManager(new Registry(db), ports(db, frames, cards))
    const s = m.registry.register({ id: "s1", name: "wt", workdir: dir, pid: 1, can_orchestrate: true })
    const r = await m.handleOrchestration({
      kind: "orchestration",
      call_id: "1",
      session_id: s.id,
      op: {
        name: "walkthrough",
        args: {
          title: "Auth",
          steps: [
            { title: "Intro", body: "hi" },
            { title: "Change", body: "here", file: "a.ts", lines: "2" },
            { title: "Miss", body: "nope", file: "gone.ts", lines: "1" },
          ],
        },
      },
    })
    expect(r.ok).toBe(true)
    const value = r.value as { steps: Array<{ status: string; title: string }> }
    expect(value.steps.map((x) => x.status)).toEqual(["ok", "ok", "not_in_diff"])
    expect(frames.some((f: any) => f.type === "walkthrough_updated" && f.walkthrough.title === "Auth")).toBe(true)
    expect(cards[0]).toBe("📖 Walkthrough ready — Auth (3 steps)")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("reply_comment threads, resolves root, and errors on unknown id", async () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const frames: object[] = []
  const m = new SessionManager(new Registry(db), ports(db, frames, []))
  const s = m.registry.register({ id: "s1", name: "wt", workdir: "/tmp", pid: 1, can_orchestrate: true })
  const review = new ReviewStore(db)
  const root = review.add({
    sessionId: s.id, repo: "", path: "a.ts", side: "RIGHT",
    anchorLine: 1, anchorContext: "x", body: "q", author: "user", createdAt: "2026-01-01",
  })
  const miss = await m.handleOrchestration({
    kind: "orchestration", call_id: "x", session_id: s.id,
    op: { name: "reply_comment", args: { comment_id: "nope", body: "hi" } },
  })
  expect(miss.ok).toBe(false)
  expect(miss.error).toMatch(/unknown comment_id/)

  const ok = await m.handleOrchestration({
    kind: "orchestration", call_id: "y", session_id: s.id,
    op: { name: "reply_comment", args: { comment_id: root.id, body: "done", resolve: true } },
  })
  expect(ok.ok).toBe(true)
  const reply = review.list(s.id).find((c) => c.parentId === root.id)
  expect(reply?.author).toBe("agent")
  expect(review.get(root.id)?.status).toBe("resolved")
  expect(review.get(root.id)?.resolvedBy).toBe("agent")
  expect(frames.filter((f: any) => f.type === "review_comment").length).toBeGreaterThanOrEqual(2)
})
