// The fake world one eval run starts from (#156): sessions, past transcripts, memory, files the user "sent", a workdir.
// Every fact a question task asks about is seeded here and can't be guessed.
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"

export const SELF_ID = "e0a1b2c3-0000-4000-8000-000000000156"

export interface Session {
  id: string; name: string; agent: string; model?: string; workdir: string; state: string; muted?: boolean
  parent?: string; children?: string[]; archived?: boolean; transcript?: string; created_at?: string; last_active?: string
  created_by?: string; access?: string; task?: string; checkout?: string; effort?: string
}
export interface World {
  self: { name: string; agent: string; workdir: string }
  sessions: Session[]
  inboxes: Record<string, Array<{ from: string; text: string; interrupt: boolean; id: string }>>
  proxies: Array<{ domain: string; port: number; public: boolean; url: string }>
  cards: Array<{ title: string; html: string }>
  attachments: Array<{ files: string[]; text?: string }>
  downloads: string[]
  councils: Array<{ id: string; question: string; members: unknown[]; ended: boolean; verdict?: string }>
  files: Record<string, string>
}

export const IDS = {
  tally: "0275cbdb-209a-4302-a948-b7a96a762b1e",
  git: "3f9c2d1e-7a4b-4c55-9e21-5d0b8a6f4c12",
  browser: "9b1e44a0-2c3d-4f8e-a1b2-77e0c5d3f901",
  release: "c4d5e6f7-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
  icd: "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9",
  jobs: "7a8b9c0d-1e2f-4a3b-b4c5-d6e7f8a9b0c1",
  webview: "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
  zmx: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  fold: "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6f",
  docs: "d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f7a",
}
/** Task 2's answer: the sessions that are idle (not working, not asleep). */
export const IDLE = [IDS.git, IDS.icd, IDS.jobs]

export function seed(rundir: string, priv: string, relay = "https://h-eval.relay.supermux.dev"): { workdir: string; muxHome: string } {
  const workdir = join(rundir, "w")
  const muxHome = join(rundir, "mux")
  for (const d of [workdir, join(workdir, "out"), join(workdir, "src"), join(muxHome, "domains"), join(priv, "inbox-files"), join(rundir, "s")]) mkdirSync(d, { recursive: true })
  const repo = "/home/ahmet/projects/supermux"
  const sessions: Session[] = [
    { id: SELF_ID, name: "Eval session", agent: "claude", model: "default", workdir, state: "working" },
    { id: IDS.tally, name: "Tally the TPM", agent: "claude", model: "opus", workdir: repo, state: "working", transcript: "user: plan the release train for v0.17.7\nassistant: Drafted the train: three cars, CI first, then the macOS updater proof." },
    { id: IDS.git, name: "Git cleanup", agent: "codex", model: "gpt-5.6-luna", workdir: repo, state: "idle", transcript: "user: tidy the branches merged into main\nassistant: Deleted 14 merged branches; waiting for your OK before touching the release branches." },
    { id: IDS.browser, name: "Browser tab (#105)", agent: "claude", model: "sonnet", workdir: "/home/ahmet/.mux/worktrees/supermux-3962b5bf/b105", state: "working",
      transcript: "user: the Browser tab drops downloads and file pickers, wire webview-compose's hooks\nassistant: Working on it: downloads now land in ~/Downloads on Linux; next is the file picker on macOS." },
    // live sessions have transcripts too (Stage 1: reading the #105 session's transcript failed in both arms)
    { id: IDS.webview, name: "Webview merge", agent: "claude", model: "sonnet", workdir: repo, state: "suspended", transcript: "user: merge webview-compose into dev\nassistant: Merged; CI green on Linux and macOS." },
    { id: IDS.release, name: "Release train", agent: "claude", model: "opus", workdir: repo, state: "suspended", transcript: "user: get the v0.17.7 train ready\nassistant: Train drafted; waiting for the Mac to build the iOS app." },
    { id: IDS.icd, name: "Infinite Calm Drive", agent: "grok", model: "grok-4.7", workdir: "/home/ahmet/projects/icd", state: "idle", transcript: "user: paint the Galata tram scene\nassistant: Tram painted; matching the golden-hour palette next." },
    { id: IDS.jobs, name: "Job Hunter", agent: "codex", model: "gpt-5.6-luna", workdir: "/home/ahmet/.mux/workspace/Job Hunter", state: "idle", transcript: "user: find remote roles in SF\nassistant: 6 leads shortlisted; drafts are in the workspace." },
    { id: IDS.zmx, name: "zmx resize crash", agent: "claude", workdir: repo, state: "archived", archived: true, created_at: "2026-11-07T10:12:00Z", last_active: "2026-11-07T15:40:00Z",
      transcript: [
        "user: the terminal dies when I rotate the phone, zmx crashes",
        "assistant: Reproduced: the zmx helper crashes with a division by zero when the pane is resized to 0 rows during rotation.",
        "assistant: Fix: clamp rows and cols to at least 1 in apps/terminal-core/zmx/helper.ts before sending the resize. Added a test for a 0-row resize. Commit 4f2a9c1.",
        "user: works now, thanks",
      ].join("\n") },
    { id: IDS.fold, name: "Fold build", agent: "claude", workdir: repo, state: "archived", archived: true, created_at: "2026-11-05T09:00:00Z", last_active: "2026-11-05T12:00:00Z",
      transcript: "user: build the Android app for the Fold\nassistant: Built and installed the debug APK on the Fold over adb." },
    { id: IDS.docs, name: "Docs site refresh", agent: "codex", workdir: repo, state: "archived", archived: true, created_at: "2026-11-02T09:00:00Z", last_active: "2026-11-02T11:00:00Z",
      transcript: "user: refresh the docs site\nassistant: Rebuilt docs-site, fixed two broken links in setup.md." },
  ]
  const world: World = {
    self: { name: "Eval session", agent: "claude", workdir }, sessions, inboxes: {},
    // two existing links (task 14); not on :5173, so task 12 can't reuse one
    proxies: [
      { domain: "fold-preview", port: 4173, public: true, url: `${relay}/p/fold-preview/` },
      { domain: "docs-dev", port: 4321, public: true, url: `${relay}/p/docs-dev/` },
    ], cards: [], attachments: [], downloads: [], councils: [],
    files: { f_7a3e91: "test-notes.txt" },
  }
  writeFileSync(join(priv, "world.json"), JSON.stringify(world, null, 1))
  writeFileSync(join(priv, "inbox-files", "test-notes.txt"), "Fold test notes, 14 Nov\n- Rotation: OK on build 2008\n- Battery drain test: passed, 6.5 hours screen-on\n- Camera: the shutter lags 900 ms in low light (still open)\n")
  // real 390×844 screenshots: a 1×1 placeholder made careful agents refuse to send "a blank image" (Stage 1)
  copyFileSync(join(import.meta.dir, "fixtures", "home.png"), join(workdir, "out", "home.png"))
  copyFileSync(join(import.meta.dir, "fixtures", "settings.png"), join(workdir, "out", "settings.png"))
  // the test task 10 is about lives in the workdir (Stage 1: agents asked "which project?" when it didn't)
  mkdirSync(join(workdir, "tests"), { recursive: true })
  writeFileSync(join(workdir, "tests", "storage-open.test.ts"), "// Opens the session store on a temp dir and closes it.\n// FLAKY on the windows CI lane: the temp dir's cleanup sometimes fails with EBUSY (a late file release).\nimport { test, expect } from \"bun:test\"\ntest(\"storage opens and closes\", async () => { expect(1).toBe(1) })\n")
  writeFileSync(join(workdir, "src", "cli.ts"), "// supermux CLI entry (eval copy)\nconst sub = process.argv[2]\nswitch (sub) { default: console.log('usage') }\n")
  writeFileSync(join(workdir, "README.md"), "# supermux (eval copy)\n")
  writeFileSync(join(muxHome, "domains", "infra.md"), [
    "# infra — dated history", "",
    "## Codex account at its usage limit (2026-11-14)",
    "The only Codex account (codex:system) hit 100% of its 30-day window; it resets on 17 Nov 2026 at 09:41 UTC. Don't start Codex sessions before then.", "",
    "## The remote Mac is nearly full (2026-11-10)",
    "An iOS build failed at 6.6 GB free. Clean DerivedData before a release build.", "",
  ].join("\n"))
  writeFileSync(join(muxHome, "domains", "claudemux.md"), [
    "# claudemux — dated history", "",
    "## Boot resumes only Agents (2026-11-02)", "A broker restart resumes Agents; workers stay asleep until a message arrives.", "",
  ].join("\n"))
  writeFileSync(join(muxHome, "domains", "bisiklet.md"), "# bisiklet — dated history\n\n## Tyre pressure (2026-10-01)\n28 mm tyres at 5.5 bar front, 5.8 rear.\n")
  writeFileSync(join(muxHome, "domains", "_inbox.md"), "# _inbox\n")
  writeFileSync(join(muxHome, "agents.md"), [
    "# .mux — knowledge domains", "", "## Domains",
    "- infra: the machines, accounts and quotas (Codex, Claude), the remote Mac",
    "- claudemux: the supermux broker internals",
    "- bisiklet: Ahmet's road bike",
    "- android: the Android app builds (Fold, Pixel), versionCode history",
    "- issues: the private kanban issue tracker for Ahmet and the agent fleet (the board); the `issues` CLI is on every PATH, see `issues --help`", "",
  ].join("\n"))
  return { workdir, muxHome }
}
