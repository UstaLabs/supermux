import { test, expect, beforeEach } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { runCurator, type CuratorDeps } from "./run"

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
