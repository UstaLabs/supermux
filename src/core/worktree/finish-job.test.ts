import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"; import { join } from "path"
import { createWorktree } from "./manager"
import { startFinishJob, getFinishJob, clearFinishJob } from "./finish-job"

function g(cwd: string, ...a: string[]) { return execFileSync("git", ["-C", cwd, ...a], { encoding: "utf-8" }).trim() }
function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-job-")); execFileSync("git", ["init", "-b", "main", dir])
  g(dir, "config", "user.email", "t@t.t"); g(dir, "config", "user.name", "t")
  writeFileSync(join(dir, "f.txt"), "1\n"); g(dir, "add", "."); g(dir, "commit", "-m", "init"); return dir
}
async function waitFor(pred: () => boolean, timeoutMs = 8000) {
  const start = Date.now()
  while (!pred()) { if (Date.now() - start > timeoutMs) throw new Error("waitFor timeout"); await new Promise((r) => setTimeout(r, 20)) }
}
const noopHooks = () => ({ onUpdate: () => {}, persist: () => {}, notify: () => {} })

test("job runs to terminal state and persists with NO subscriber", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  clearFinishJob("sess1")
  const persisted: any[] = []
  let notified = 0
  const session = { id: "sess1", repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }
  const job = startFinishJob(session, { action: "merge", skipVerify: true, cleanup: false }, { onUpdate: () => {}, persist: (j) => persisted.push({ ...j }), notify: () => { notified++ } })
  expect(job.status).toBe("running")
  await waitFor(() => getFinishJob("sess1")?.status !== "running")
  const done = getFinishJob("sess1")!
  expect(done.status).toBe("done")
  expect(done.outcome?.status).toBe("integrated")
  expect(notified).toBe(1)                       // notified exactly once, on terminal
  expect(persisted.length).toBeGreaterThan(0)     // state was persisted during/after the run
})

test("a failing outcome marks the job failed", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  // dirty + no commitFirst → finishWorktree returns "uncommitted" (a failure)
  writeFileSync(join(h.worktreeDir, "a.txt"), "x")
  clearFinishJob("sess2")
  const session = { id: "sess2", repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }
  startFinishJob(session, { action: "merge", skipVerify: true, cleanup: false }, noopHooks())
  await waitFor(() => getFinishJob("sess2")?.status !== "running")
  expect(getFinishJob("sess2")!.status).toBe("failed")
  expect(getFinishJob("sess2")!.outcome?.status).toBe("uncommitted")
})

test("second start while running returns the same job (one per session)", async () => {
  const repo = tmpRepo()
  const h = await createWorktree({ repoRoot: repo, baseBranch: "main", sessionName: "s" })
  writeFileSync(join(h.worktreeDir, "a.txt"), "x"); g(h.worktreeDir, "add", "."); g(h.worktreeDir, "commit", "-m", "w")
  clearFinishJob("sess3")
  const session = { id: "sess3", repoRoot: repo, worktreeDir: h.worktreeDir, sessionBranch: h.sessionBranch, baseBranch: "main" }
  const j1 = startFinishJob(session, { action: "merge", skipVerify: true, cleanup: false }, noopHooks())
  const j2 = startFinishJob(session, { action: "merge", skipVerify: true, cleanup: false }, noopHooks())
  expect(j2.startedAt).toBe(j1.startedAt)   // same job object, not a new run
  await waitFor(() => getFinishJob("sess3")?.status !== "running")
})
