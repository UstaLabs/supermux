import { test } from "node:test"
import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { devNull, tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { fileURLToPath } from "node:url"
import { startServer } from "./support/server.mjs"
import { openEvents } from "./support/sse.mjs"

const script = fileURLToPath(new URL("../examples/kanban/server.ts", import.meta.url))
// startServer() puts the stub agent CLIs on PATH, and they are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// The Claude fixture takes an allow only when told to expect one: otherwise it exits, and the turn fails.
const env = { EXPECT_PERM: "allow" }
// The test's own git commands leave the user's git config out (a signing or hook setting would change them).
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: devNull, GIT_CONFIG_NOSYSTEM: "1" }
const git = (cwd, ...args) => execFileSync("git", args, { cwd, encoding: "utf8", env: gitEnv }).trim()

/** A state directory of its own, outside the repository, so a test never writes to the real home. */
const mkState = () => mkdtempSync(join(tmpdir(), "kanban-state-"))

const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}
/** Whether `pid` ends up gone within five seconds (a SIGKILLed process is reaped a beat later). */
async function dead(pid) {
  const deadline = Date.now() + 5_000
  while (alive(pid) && Date.now() < deadline) await sleep(20)
  return !alive(pid)
}

/** A git repository with one commit, in a new temporary directory. */
function makeRepo() {
  const repo = mkdtempSync(join(tmpdir(), "kanban-e2e-"))
  git(repo, "init", "-q", "-b", "main")
  git(repo, "config", "user.email", "e2e@example.com")
  git(repo, "config", "user.name", "e2e")
  writeFileSync(join(repo, "README.md"), "hello\n")
  git(repo, "add", ".")
  git(repo, "commit", "-qm", "init")
  return repo
}

/** Card `id` as the first board event at or after `events.seen[from]` shows it in `column` (and passing `also`). */
async function reach(events, id, column, { from = 0, also = () => true } = {}) {
  const board = await events.next(m => m.type === "board" && events.seen.indexOf(m) >= from
    && m.cards.some(card => card.id === id && card.column === column && also(card)))
  return board.cards.find(card => card.id === id)
}

test("kanban: a card runs in its own worktree, reaches review, and discard cleans up", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    // No other site may frame the page, to trick a click on "Allow".
    const page = await fetch(server.url + "/")
    const framing = ["x-frame-options", "content-security-policy"].map(name => page.headers.get(name))
    assert.deepEqual(framing, ["DENY", "frame-ancestors 'none'"])
    assert.match(await page.text(), /id="board"/)
    assert.deepEqual(await server.get("/api/agents"), ["codex"])

    const events = await openEvents(server.url + "/api/events")
    // A new stream opens on the whole board straight away, before anything changes.
    assert.deepEqual((await events.next(m => m.type === "board")).cards, [])
    // An id that is not eight hex digits is no card: 404, stating the contract.
    await assert.rejects(server.get("/api/cards/zzzzzzzz/diff"), /404/)
    ;({ id } = await server.post("/api/cards", { title: "Say hello", prompt: "hello", agent: "codex" }))
    assert.match(id, /^[0-9a-f]{8}$/)
    assert.equal((await reach(events, id, "backlog")).title, "Say hello")
    await server.post(`/api/cards/${id}/start`)
    await reach(events, id, "running")
    const card = await reach(events, id, "review")
    assert.equal(card.branch, `agent/${id}`)
    assert.equal(card.base, git(repo, "rev-parse", "HEAD"))
    assert.ok(existsSync(card.worktree))
    assert.match(git(repo, "branch", "--list", `agent/${id}`), new RegExp(`agent/${id}`))
    // The codex stub answered, not a real CLI.
    assert.deepEqual(server.stubs.launched(), ["codex"])

    // The diff is text, and shows what the agent left in its worktree, a new file included.
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    writeFileSync(join(card.worktree, "NEW.md"), "new\n")
    assert.match(await server.get(`/api/cards/${id}/diff`), /^\+\+\+ b\/NEW\.md$/m)
    // A diff over the cap is a 413, not a maxBuffer failure.
    writeFileSync(join(card.worktree, "BIG.txt"), "x".repeat(6 * 1024 * 1024))
    await assert.rejects(server.get(`/api/cards/${id}/diff`), /413/)

    // Discard takes the worktree, the branch and the card; nothing of the state directory shows in git status.
    await server.post(`/api/cards/${id}/discard`)
    assert.equal(existsSync(card.worktree), false)
    assert.equal(git(repo, "branch", "--list", `agent/${id}`), "")
    assert.deepEqual(await server.get("/api/board"), { cards: [] })
    assert.equal(git(repo, "status", "--porcelain"), "")
    events.close()
  } finally {
    // A failure above must not leave the agent running behind its keeper.
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a planted .git cannot make a git command run the agent's code", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  const scratch = mkdtempSync(join(tmpdir(), "kanban-c1-"))
  const marker = join(scratch, "MARKER")
  const payload = join(scratch, "payload.sh")
  writeFileSync(payload, `#!/bin/sh\necho pwned > ${JSON.stringify(marker)}\n`)
  chmodSync(payload, 0o755)
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "work", prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    const card = await reach(events, id, "review")

    // The agent replaces its worktree's .git with a pointer to a git directory of its own, whose config runs the
    // payload as a filesystem monitor: a `git -C <worktree> …` that trusted the .git file would execute it.
    const wt = card.worktree
    const planted = join(wt, ".planted")
    git(wt, "init", "-q", "--bare", planted)
    git(planted, "config", "core.fsmonitor", payload)
    git(planted, "config", "core.bare", "false")
    writeFileSync(join(wt, ".git"), "gitdir: .planted\n")

    // The diff endpoint drives `git add` and `git diff` over the worktree; neither may run the payload.
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    assert.equal(existsSync(marker), false, "a git command ran the planted payload")
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    assert.equal(existsSync(marker), false, "discard ran the planted payload")
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
    rmSync(scratch, { recursive: true, force: true })
  }
})

test("kanban: two Starts race to one agent, and Discard never orphans a Start", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await openEvents(server.url + "/api/events")

    // Two concurrent Starts: exactly one runs the agent, the other is a 409.
    ;({ id } = await server.post("/api/cards", { title: "once", prompt: "hello", agent: "codex" }))
    const starts = await Promise.allSettled([
      server.post(`/api/cards/${id}/start`), server.post(`/api/cards/${id}/start`),
    ])
    const ok = starts.filter(r => r.status === "fulfilled")
    const rejected = starts.filter(r => r.status === "rejected")
    assert.equal(ok.length, 1, "both Starts were accepted")
    assert.equal(rejected.length, 1)
    assert.match(rejected[0].reason.message, /409/)
    await reach(events, id, "review")
    assert.deepEqual(server.stubs.launched(), ["codex"], "the agent was launched more than once")
    await server.post(`/api/cards/${id}/discard`)

    // A Discard racing a Start is a 409 or a clean result; either way nothing is left off the board.
    const other = (await server.post("/api/cards", { title: "race", prompt: "hello", agent: "codex" })).id
    const race = await Promise.allSettled([
      server.post(`/api/cards/${other}/start`), server.post(`/api/cards/${other}/discard`),
    ])
    for (const r of race) if (r.status === "rejected") assert.match(r.reason.message, /409/)
    // Whatever the race, settle the card and make sure it leaves nothing behind.
    if ((await server.get("/api/board")).cards.some(c => c.id === other)) {
      await server.post(`/api/cards/${other}/discard`).catch(() => {})
    }
    assert.equal(existsSync(join(state, "worktrees", other)), false)
    assert.equal(git(repo, "branch", "--list", `agent/${other}`), "")
    assert.equal(existsSync(join(state, "keepers", other)), false)
    events.close()
  } finally {
    for (const card of (await server?.get("/api/board").catch(() => ({ cards: [] }))).cards) {
      await server?.post(`/api/cards/${card.id}/discard`).catch(() => {})
    }
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a Start whose worktree add failed can be discarded and started again", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  // A post-checkout hook in the main repo that fails: `git worktree add` leaves the branch and the worktree but exits
  // non-zero, so the first Start fails after creating them.
  const hook = join(repo, ".git", "hooks", "post-checkout")
  writeFileSync(hook, "#!/bin/sh\nexit 1\n")
  chmodSync(hook, 0o755)
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "partial", prompt: "hello", agent: "codex" }))
    await assert.rejects(server.post(`/api/cards/${id}/start`), /409/)
    // The failed add left the branch and a registered worktree, but the card stayed in the backlog.
    assert.match(git(repo, "branch", "--list", `agent/${id}`), new RegExp(`agent/${id}`))
    assert.equal((await server.get("/api/board")).cards.find(c => c.id === id).column, "backlog")

    // With the hook gone, Start reuses the existing branch and worktree and reaches review.
    rmSync(hook)
    await server.post(`/api/cards/${id}/start`)
    const card = await reach(events, id, "review")
    assert.equal(card.branch, `agent/${id}`)
    assert.ok(existsSync(card.worktree))
    assert.deepEqual(server.stubs.launched(), ["codex"])
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a review card whose idle agent dies stays in review with its error", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "claude", "--state", state], env)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "done work", prompt: "hello", agent: "claude" }))
    await server.post(`/api/cards/${id}/start`)
    await reach(events, id, "review")

    // The idle agent and its keeper die under the card.
    const status = JSON.parse(readFileSync(join(state, "keepers", id, "status.json"), "utf8"))
    const from = events.seen.length
    process.kill(status.keeperPid, "SIGKILL")
    try { process.kill(status.agentPid, "SIGKILL") } catch { /* the keeper took it */ }

    // The card keeps its finished work: it stays in review with an error, rather than returning to the backlog where a
    // Start would resend the original prompt.
    const failed = await reach(events, id, "review", { from, also: card => Boolean(card.error) })
    assert.ok(failed.error)
    assert.equal((await server.get("/api/board")).cards.find(c => c.id === id).column, "review")
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: Discard after a restart stops the detached agent and removes its keeper", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  const args = ["--repo", repo, "--agents", "codex", "--state", state]
  let server
  let id
  let status
  try {
    server = await startServer(script, args)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "detach", prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    await reach(events, id, "review")
    status = JSON.parse(readFileSync(join(state, "keepers", id, "status.json"), "utf8"))
    events.close()

    // SIGTERM detaches: the agent keeps running behind its keeper.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    assert.ok(alive(status.keeperPid), "the keeper stopped with the detaching server")

    // The next server does not re-attach a review card; Discard then stops the detached agent and removes its keeper.
    server = await startServer(script, args)
    await server.post(`/api/cards/${id}/discard`)
    assert.ok(await dead(status.keeperPid), "the keeper outlived the discard")
    assert.ok(await dead(status.agentPid), "the agent outlived the discard")
    assert.equal(existsSync(join(state, "keepers", id)), false, "the keeper directory outlived the discard")
    assert.deepEqual(await server.get("/api/board"), { cards: [] })
    assert.equal(existsSync(join(state, "worktrees", id)), false)
    assert.equal(git(repo, "branch", "--list", `agent/${id}`), "")
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    for (const pid of [status?.keeperPid, status?.agentPid]) if (pid && alive(pid)) process.kill(pid, "SIGKILL")
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a permission prompt puts the card in needs-you, and the answer puts it back to work", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "claude", "--state", state], env)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "Ask first", prompt: "permission", agent: "claude" }))
    await server.post(`/api/cards/${id}/start`)
    const asking = await reach(events, id, "needs-you")
    assert.equal(asking.request.kind, "permission-request")
    const from = events.seen.length
    const allow = asking.request.options.find(option => option.kind === "allow_once")
    await server.post(`/api/cards/${id}/requests/${asking.request.requestId}`, { optionId: allow.id })
    assert.equal((await reach(events, id, "running", { from })).request, undefined)
    await reach(events, id, "review", { from })
    assert.deepEqual(server.stubs.launched(), ["claude"])
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a follow-up from review runs the agent again, and Done keeps the branch, the worktree and the card", { skip }, async () => {
  const repo = makeRepo()
  // An explicit --state inside the repository is excluded from git once; the default lives outside it.
  const state = join(repo, ".supermux-kanban")
  const args = ["--repo", repo, "--agents", "claude", "--state", state]
  let server
  let id
  try {
    server = await startServer(script, args, env)
    const events = await openEvents(server.url + "/api/events")
    ;({ id } = await server.post("/api/cards", { title: "Say hello", prompt: "hello", agent: "claude" }))
    await server.post(`/api/cards/${id}/start`)
    // The diff stat comes with the review: nothing changed yet. The status line is the agent's last word.
    const first = await reach(events, id, "review", { also: card => card.diffStat !== undefined })
    assert.equal(first.diffStat, "")
    await events.next(m => m.type === "activity" && m.cardId === id && m.text === "héllo")

    // What the agent leaves in its worktree shows in the next review's stat.
    writeFileSync(join(first.worktree, "NEW.md"), "new\n")
    const from = events.seen.length
    await server.post(`/api/cards/${id}/followup`, { text: "hello again" })
    await reach(events, id, "running", { from })
    const second = await reach(events, id, "review", { from, also: card => /1 file changed/.test(card.diffStat ?? "") })
    assert.match(second.diffStat, /1 file changed, 1 insertion/)

    await server.post(`/api/cards/${id}/done`)
    const done = (await server.get("/api/board")).cards.find(card => card.id === id)
    assert.equal(done.column, "done")
    assert.match(git(repo, "branch", "--list", `agent/${id}`), new RegExp(`agent/${id}`))
    assert.ok(existsSync(join(done.worktree, "NEW.md")))
    events.close()

    // The board outlives the server; a done card's agent is not started again, and the state directory is
    // excluded from git once.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, args, env)
    assert.deepEqual(await server.get("/api/board"), { cards: [done] })
    assert.deepEqual(server.stubs.launched(), [])
    const exclude = readFileSync(join(repo, ".git", "info", "exclude"), "utf8").split("\n")
    assert.deepEqual(exclude.filter(line => line.includes(".supermux-kanban")), ["/.supermux-kanban/"])
    assert.equal(git(repo, "status", "--porcelain"), "")
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
  }
})

test("kanban: a --repo that is not a git work tree stops the start, saying so in one line", { skip }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "kanban-e2e-"))
  try {
    await assert.rejects(startServer(script, ["--repo", dir, "--agents", "codex"]), error => {
      assert.match(error.message, /^server exited with code 1\n/)
      assert.match(error.message.split("\nstderr: ")[1], /^[^\n]*is not a git work tree[^\n]*\n$/)
      return true
    })
    assert.equal(existsSync(join(dir, ".supermux-kanban")), false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
