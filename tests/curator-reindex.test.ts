import { test, expect } from "bun:test"
import { runCurator, type CuratorDeps } from "../src/core/curator/run"
import { writeFileSync, mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

function deps(over: Partial<CuratorDeps>): CuratorDeps {
  const promptPath = join(mkdtempSync(join(tmpdir(), "mux-cur-")), "p.md")
  writeFileSync(promptPath, "do the thing")
  return {
    chatId: "web", repoPath: "/tmp", promptPath,
    spawn: async () => ({ name: "nightly-curator" }),
    waitReady: async () => "sid-1",
    sendInbound: async () => {},
    isIdle: (() => { let n = 0; return () => ++n > 2 })(), // active then idle
    getActive: () => undefined, setActive: () => {}, archive: () => {},
    postNotice: async () => {},
    sleep: async () => {},
    reindex: () => {}, ...over,
  }
}

test("runCurator calls reindex after completion", async () => {
  let reindexed = false
  await runCurator(deps({ reindex: () => { reindexed = true } }))
  expect(reindexed).toBe(true)
})
