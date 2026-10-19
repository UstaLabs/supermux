import { test } from "node:test"
import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { devNull, tmpdir } from "node:os"
import { join } from "node:path"
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
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex"])
    // No other site may frame the page, to trick a click on "Allow".
    const page = await fetch(server.url + "/")
    const framing = ["x-frame-options", "content-security-policy"].map(name => page.headers.get(name))
    assert.deepEqual(framing, ["DENY", "frame-ancestors 'none'"])
    assert.match(await page.text(), /id="board"/)
    assert.deepEqual(await server.get("/api/agents"), ["codex"])

    const events = await openEvents(server.url + "/api/events")
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

    // Discard takes the worktree, the branch and the card; the state directory never shows in git status.
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
  }
})

test("kanban: a permission prompt puts the card in needs-you, and the answer puts it back to work", { skip }, async () => {
  const repo = makeRepo()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "claude"], env)
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
  }
})

test("kanban: a follow-up from review runs the agent again, and Done keeps the branch, the worktree and the card", { skip }, async () => {
  const repo = makeRepo()
  const args = ["--repo", repo, "--agents", "claude"]
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
