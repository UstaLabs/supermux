import { test, expect, beforeEach, afterEach } from "bun:test"
import { existsSync, mkdtempSync, rmSync, unlinkSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

// `/projects` must surface real repos, never the throwaway worktree dirs under
// ~/.mux/worktrees — so the new-session project picker stays clean.

const DEV_PATH = `/tmp/devices-projects-wt-${process.pid}.json`
const PORT = 18900 + Math.floor(Math.random() * 100)
let ch: WebChannel
let token: string
let tmpRoot: string
let oldHome: string | undefined

beforeEach(async () => {
  __resetAuthFailures()
  tmpRoot = mkdtempSync(join(tmpdir(), "mux-projects-wt-"))
  oldHome = process.env.HOME
  process.env.HOME = tmpRoot
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
  const store = new DeviceStore(DEV_PATH)
  token = store.mint("test-device").token

  const wt = join(tmpRoot, ".mux", "worktrees")
  ch = new WebChannel({
    port: PORT,
    devicesFile: DEV_PATH,
    publicUrl: "http://127.0.0.1:" + PORT,
    getSessionsSnapshot: () => [
      // worktree-backed active session → should surface its repo_root, not the worktree
      { id: "wt", name: "wt", workdir: join(wt, "myrepo-abc", "uuid1"), repo_root: join(tmpRoot, "myrepo"), mute: false, connected: true, agent: "claude" as const },
      // a plain project session → shown as-is
      { id: "plain", name: "plain", workdir: join(tmpRoot, "plain-project"), mute: false, connected: true, agent: "claude" as const },
    ],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    // an archived session living in a worktree dir (no repo_root in its snapshot) → dropped
    listArchivedSessions: () => [{ id: "old", name: "old", workdir: join(wt, "oldrepo-def", "uuid2"), agent: "claude" as const }],
  } as any)
  await ch.start()
})

afterEach(async () => {
  await ch.stop()
  if (oldHome === undefined) delete process.env.HOME
  else process.env.HOME = oldHome
  rmSync(tmpRoot, { recursive: true, force: true })
  if (existsSync(DEV_PATH)) unlinkSync(DEV_PATH)
})

function authed() {
  return { Cookie: `cmux_token=${token}`, "content-type": "application/json" }
}

test("GET /projects surfaces repo_root for worktree sessions and hides worktree dirs", async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/projects`, { headers: authed() })
  expect(res.status).toBe(200)
  const body = await res.json() as { projects: Array<{ path: string }> }
  const paths = body.projects.map((p) => p.path)
  expect(paths).toEqual([join(tmpRoot, "myrepo"), join(tmpRoot, "plain-project")])
  // no path under ~/.mux/worktrees leaks through
  expect(paths.some((p) => p.includes(join(".mux", "worktrees")))).toBe(false)
})
