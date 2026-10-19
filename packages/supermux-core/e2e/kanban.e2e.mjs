import { after, test } from "node:test"
import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { devNull, homedir, tmpdir } from "node:os"
import { basename, join } from "node:path"
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

// A state directory of its own: board.json, the core's records in core/, and worktrees/ beside it (the agents' writable
// roots are fine for these).
const mkState = () => mkdtempSync(join(tmpdir(), "kanban-state-"))
// The repository must live outside the agents' writable roots, so these tests keep it under the home, not in /tmp.
const repoBase = mkdtempSync(join(homedir(), ".kanban-e2e-"))
after(() => rmSync(repoBase, { recursive: true, force: true }))

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

/** A git repository with one commit, outside the agents' writable roots (under the home, not /tmp). */
function makeRepo() {
  const repo = mkdtempSync(join(repoBase, "repo-"))
  git(repo, "init", "-q", "-b", "main")
  git(repo, "config", "user.email", "e2e@example.com")
  git(repo, "config", "user.name", "e2e")
  writeFileSync(join(repo, "README.md"), "hello\n")
  git(repo, "add", ".")
  git(repo, "commit", "-qm", "init")
  return repo
}

/** What the board says of `repo`: its name, and the full path of its top directory. */
function repoOf(repo) {
  const path = git(repo, "rev-parse", "--show-toplevel")
  return { name: basename(path), path }
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
    const page = await server.fetch("/")
    const framing = ["x-frame-options", "content-security-policy"].map(name => page.headers.get(name))
    assert.deepEqual(framing, ["DENY", "frame-ancestors 'none'"])
    assert.match(await page.text(), /id="board"/)
    assert.deepEqual(await server.get("/api/agents"), ["codex"])

    const events = await server.events()
    // A new stream opens on the whole board straight away, before anything changes: the repository, and no cards.
    assert.deepEqual(await events.next(m => m.type === "board"), { type: "board", repo: repoOf(repo), cards: [] })
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
    // A name that is not ASCII shows as it is, not octal-escaped in quotes.
    writeFileSync(join(card.worktree, "café.md"), "new\n")
    assert.match(await server.get(`/api/cards/${id}/diff`), /^\+\+\+ b\/café\.md$/m)
    // A diff over the cap is a 413, not a maxBuffer failure.
    writeFileSync(join(card.worktree, "BIG.txt"), "x".repeat(6 * 1024 * 1024))
    await assert.rejects(server.get(`/api/cards/${id}/diff`), /413/)

    // Discard takes the worktree, the branch and the card; nothing of the state directory shows in git status.
    await server.post(`/api/cards/${id}/discard`)
    assert.equal(existsSync(card.worktree), false)
    assert.equal(git(repo, "branch", "--list", `agent/${id}`), "")
    assert.deepEqual(await server.get("/api/board"), { repo: repoOf(repo), cards: [] })
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
    const events = await server.events()
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
    // Done's `git add` and `git commit` go through the same pinned git directory.
    await server.post(`/api/cards/${id}/done`)
    assert.equal(existsSync(marker), false, "Done's commit ran the planted payload")
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
    const events = await server.events()

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
    assert.equal(existsSync(join(state, "core", "keepers", other)), false)
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
  const worktrees = join(state, "worktrees")
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await server.events()
    ;({ id } = await server.post("/api/cards", { title: "partial", prompt: "hello", agent: "codex" }))
    // A read-only worktrees directory makes `git worktree add` create the branch, then fail to check out: the first
    // Start fails after the branch exists (the board runs no repository hooks now, so a failing hook cannot do this).
    chmodSync(worktrees, 0o500)
    await assert.rejects(server.post(`/api/cards/${id}/start`), /409/)
    chmodSync(worktrees, 0o700)
    assert.match(git(repo, "branch", "--list", `agent/${id}`), new RegExp(`agent/${id}`))
    assert.equal((await server.get("/api/board")).cards.find(c => c.id === id).column, "backlog")

    // With the directory writable again, Start reuses the existing branch and reaches review.
    await server.post(`/api/cards/${id}/start`)
    const card = await reach(events, id, "review")
    assert.equal(card.branch, `agent/${id}`)
    assert.ok(existsSync(card.worktree))
    assert.deepEqual(server.stubs.launched(), ["codex"])
    events.close()
  } finally {
    if (existsSync(worktrees)) chmodSync(worktrees, 0o700)
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a submodule the agent fills with a clean filter never runs it", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  const scratch = mkdtempSync(join(tmpdir(), "kanban-sub-"))
  const marker = join(scratch, "MARKER")
  const payload = join(scratch, "clean.sh")
  writeFileSync(payload, `#!/bin/sh\necho pwned > ${JSON.stringify(marker)}\ncat\n`)
  chmodSync(payload, 0o755)
  // A real submodule in the repository's base commit.
  const src = join(scratch, "src")
  mkdirSync(src)
  git(src, "init", "-q", "-b", "main")
  git(src, "config", "user.email", "a@b")
  git(src, "config", "user.name", "n")
  writeFileSync(join(src, "x"), "orig\n")
  git(src, "add", ".")
  git(src, "commit", "-qm", "s")
  git(repo, "-c", "protocol.file.allow=always", "submodule", "add", src, "subdir")
  git(repo, "commit", "-qm", "with submodule")
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await server.events()
    ;({ id } = await server.post("/api/cards", { title: "sub", prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    const card = await reach(events, id, "review")

    // The agent fills the submodule directory with a repository of its own whose clean filter is the payload.
    const subdir = join(card.worktree, "subdir")
    mkdirSync(subdir, { recursive: true })
    git(subdir, "init", "-q", "-b", "main")
    git(subdir, "config", "filter.x.clean", payload)
    writeFileSync(join(subdir, ".gitattributes"), "data filter=x\n")
    writeFileSync(join(subdir, "data"), "secret\n")
    // The payload is live: running the submodule's own clean filter writes the marker.
    git(subdir, "add", "-A")
    assert.ok(existsSync(marker), "the payload clean filter did not run even directly")
    git(subdir, "-c", "user.email=a@b", "-c", "user.name=n", "commit", "-qm", "s")
    rmSync(marker)
    writeFileSync(join(subdir, "data"), "changed\n")

    // The diff endpoint drives `git add` and `git diff` over the worktree; neither may enter the submodule.
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    assert.equal(existsSync(marker), false, "a git command ran the submodule's clean filter")
    // Done's commit leaves the submodule out too.
    await server.post(`/api/cards/${id}/done`)
    assert.equal(existsSync(marker), false, "Done's commit ran the submodule's clean filter")
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    assert.equal(existsSync(marker), false, "discard ran the submodule's clean filter")
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
    rmSync(scratch, { recursive: true, force: true })
  }
})

test("kanban: repository hooks do not run for the worktrees the board creates, nor for Done's commit", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  const marker = join(state, "HOOK-RAN")
  // A post-checkout hook in the repository would run on `git worktree add`, and the commit hooks on Done's commit,
  // unless the board turns hooks off.
  for (const name of ["post-checkout", "pre-commit", "commit-msg", "post-commit"]) {
    const hook = join(repo, ".git", "hooks", name)
    writeFileSync(hook, `#!/bin/sh\necho ${name} >> ${JSON.stringify(marker)}\n`)
    chmodSync(hook, 0o755)
  }
  // Nor has the repository an identity (and the server's HOME is an empty one): Done commits as the board.
  git(repo, "config", "--unset", "user.name")
  git(repo, "config", "--unset", "user.email")
  // It asks for signed commits, with a signing program that would need a terminal: board commits are not signed.
  const signer = join(state, "gpg.sh")
  writeFileSync(signer, `#!/bin/sh\necho gpg >> ${JSON.stringify(marker)}\nexit 1\n`)
  chmodSync(signer, 0o755)
  git(repo, "config", "commit.gpgSign", "true")
  git(repo, "config", "gpg.program", signer)
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await server.events()
    ;({ id } = await server.post("/api/cards", { title: "hooked", prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    // Once the review has its stat (nothing changed yet), the agent "writes" a file.
    const card = await reach(events, id, "review", { also: card => card.diffStat === "" })
    writeFileSync(join(card.worktree, "NEW.md"), "new\n")
    const { commit } = await server.post(`/api/cards/${id}/done`)
    assert.equal(git(repo, "log", "-1", "--format=%an <%ae>", commit), "supermux kanban <kanban@supermux.invalid>")
    assert.doesNotMatch(git(repo, "cat-file", "commit", commit), /^gpgsig /m, "the board signed its commit")
    // The stat is taken again at Done: the review's said nothing had changed yet.
    assert.equal((await server.get("/api/board")).cards.find(c => c.id === id).diffStat, "1 file changed, 1 insertion(+)")
    assert.equal(existsSync(marker), false, `a repository hook ran: ${existsSync(marker) && readFileSync(marker, "utf8")}`)
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a --repo inside a temporary directory is refused", { skip }, async () => {
  // A repository the agents could write to (a /tmp checkout) is refused before the server starts.
  const repo = mkdtempSync(join(tmpdir(), "kanban-tmp-repo-"))
  git(repo, "init", "-q", "-b", "main")
  git(repo, "config", "user.email", "a@b")
  git(repo, "config", "user.name", "n")
  writeFileSync(join(repo, "README.md"), "hi\n")
  git(repo, "add", ".")
  git(repo, "commit", "-qm", "init")
  try {
    await assert.rejects(startServer(script, ["--repo", repo, "--agents", "codex"]), error => {
      assert.match(error.message, /^server exited with code 1\n/)
      assert.match(error.message.split("\nstderr: ")[1], /keep the repo outside the agents' writable roots/)
      return true
    })
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
})

test("kanban: a review card whose idle agent dies stays in review with its error", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "claude", "--state", state], env)
    const events = await server.events()
    ;({ id } = await server.post("/api/cards", { title: "done work", prompt: "hello", agent: "claude" }))
    await server.post(`/api/cards/${id}/start`)
    await reach(events, id, "review")

    // The idle agent and its keeper die under the card.
    const status = JSON.parse(readFileSync(join(state, "core", "keepers", id, "status.json"), "utf8"))
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
    const events = await server.events()
    ;({ id } = await server.post("/api/cards", { title: "detach", prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    await reach(events, id, "review")
    status = JSON.parse(readFileSync(join(state, "core", "keepers", id, "status.json"), "utf8"))
    events.close()

    // SIGTERM detaches: the agent keeps running behind its keeper.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    assert.ok(alive(status.keeperPid), "the keeper stopped with the detaching server")

    // The next server does not re-attach a review card; Discard then stops the detached agent and removes its keeper.
    server = await startServer(script, args)
    await server.post(`/api/cards/${id}/discard`)
    assert.ok(await dead(status.keeperPid), "the keeper outlived the discard")
    assert.ok(await dead(status.agentPid), "the agent outlived the discard")
    assert.equal(existsSync(join(state, "core", "keepers", id)), false, "the keeper directory outlived the discard")
    assert.deepEqual(await server.get("/api/board"), { repo: repoOf(repo), cards: [] })
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
    const events = await server.events()
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

    // Done with nothing changed commits nothing, and says so: no commit, and none on the branch.
    assert.deepEqual(await server.post(`/api/cards/${id}/done`), { ok: true, commit: null, skipped: [] })
    assert.equal((await server.get("/api/board")).cards.find(card => card.id === id).commits, 0)
    assert.equal(git(repo, "rev-list", "--count", `main..agent/${id}`), "0")
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: Done commits on the card's branch only, leaves out the agent's own repositories, and takes one title line", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  let server
  let id
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    const events = await server.events()
    // A title made through the API may hold a newline: only its first line becomes the message, so no trailer sneaks in.
    const title = "Tidy up\nSigned-off-by: Mallory <mallory@example.com>"
    ;({ id } = await server.post("/api/cards", { title, prompt: "hello", agent: "codex" }))
    await server.post(`/api/cards/${id}/start`)
    const card = await reach(events, id, "review", { also: card => card.diffStat === "" })
    writeFileSync(join(card.worktree, "NEW.md"), "new\n")

    // A worktree the agent moved to another branch, or detached, is refused: the commit would land off agent/<id>.
    git(card.worktree, "switch", "-q", "-c", "elsewhere")
    await assert.rejects(server.post(`/api/cards/${id}/done`), /409.*off agent\/[0-9a-f]{8} \(to elsewhere\)/)
    git(card.worktree, "switch", "-q", "--detach")
    await assert.rejects(server.post(`/api/cards/${id}/done`), /409.*off agent\/[0-9a-f]{8} \(HEAD is detached\)/)
    assert.equal((await server.get("/api/board")).cards.find(c => c.id === id).column, "review")
    assert.equal(git(repo, "rev-list", "--count", `${card.base}..elsewhere`), "0")
    git(card.worktree, "switch", "-q", `agent/${id}`)

    // Repositories the agent made in its worktree (an empty one too) stay out of the commit, even once a diff saw them.
    git(card.worktree, "init", "-q", "empty")
    git(card.worktree, "init", "-q", "nested")
    writeFileSync(join(card.worktree, "nested", "x"), "x\n")
    git(join(card.worktree, "nested"), "add", ".")
    git(join(card.worktree, "nested"), "-c", "user.name=n", "-c", "user.email=a@b", "commit", "-qm", "nested")
    assert.equal(typeof await server.get(`/api/cards/${id}/diff`), "string")
    const { commit, skipped } = await server.post(`/api/cards/${id}/done`)
    assert.deepEqual(skipped, ["empty", "nested"])
    assert.equal(git(repo, "show", "--name-only", "--format=", commit), "NEW.md")
    assert.equal(git(repo, "log", "-1", "--format=%B", commit), "Tidy up")
    const done = (await server.get("/api/board")).cards.find(c => c.id === id)
    assert.equal(done.commits, 1)
    assert.equal(done.diffStat, "1 file changed, 1 insertion(+)")
    events.close()
  } finally {
    if (id) await server?.post(`/api/cards/${id}/discard`).catch(() => {})
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a state directory from before the core had core/ of its own still starts", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  // The old layout: the core's records beside board.json, and a card at work whose session is in them.
  mkdirSync(join(state, "sessions"), { recursive: true })
  writeFileSync(join(state, "board.json"), JSON.stringify({ cards: [
    { id: "0123abcd", title: "was running", prompt: "hello", agent: "codex", column: "running", branch: "agent/0123abcd",
      worktree: join(state, "worktrees", "0123abcd") },
    { id: "4567ef01", title: "was in the backlog", prompt: "hello", agent: "codex", column: "backlog" },
  ] }))
  let server
  try {
    server = await startServer(script, ["--repo", repo, "--agents", "codex", "--state", state])
    // The card whose agent cannot come back goes to the backlog, saying why; the other starts as any card does.
    let moved
    for (let tries = 0; tries < 100 && !moved; tries++) {
      moved = (await server.get("/api/board")).cards.find(card => card.id === "0123abcd" && card.column === "backlog")
      if (!moved) await sleep(50)
    }
    assert.ok(moved?.error, "the card at work did not go back to the backlog with its error")
    await server.post("/api/cards/4567ef01/start")
    assert.ok(existsSync(join(state, "worktrees", "4567ef01")))
    for (const id of ["0123abcd", "4567ef01"]) await server.post(`/api/cards/${id}/discard`)
    assert.deepEqual((await server.get("/api/board")).cards, [])
  } finally {
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})

test("kanban: a follow-up from review runs the agent again, and Done commits the work on the branch it keeps", { skip }, async () => {
  const repo = makeRepo()
  // An explicit --state inside the repository is excluded from git once; the default lives outside it.
  const state = join(repo, ".supermux-kanban")
  const args = ["--repo", repo, "--agents", "claude", "--state", state]
  let server
  let id
  try {
    server = await startServer(script, args, env)
    const events = await server.events()
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

    // Done commits what the agent changed on its branch, titled after the card: `git merge agent/<id>` merges it.
    const { commit } = await server.post(`/api/cards/${id}/done`)
    const done = (await server.get("/api/board")).cards.find(card => card.id === id)
    assert.equal(done.column, "done")
    assert.equal(done.commits, 1)
    assert.equal(git(repo, "rev-parse", `agent/${id}`), commit)
    assert.equal(git(repo, "rev-list", "--count", `${first.base}..agent/${id}`), "1")
    assert.equal(git(repo, "log", "-1", "--format=%s|%an", `agent/${id}`), "Say hello|e2e")
    assert.equal(git(repo, "show", "--name-only", "--format=", `agent/${id}`), "NEW.md")
    // The worktree stays, with nothing left uncommitted.
    assert.ok(existsSync(join(done.worktree, "NEW.md")))
    assert.equal(git(done.worktree, "status", "--porcelain"), "")
    events.close()

    // The board outlives the server; a done card's agent is not started again, and the state directory is
    // excluded from git once.
    assert.deepEqual(await server.stop("SIGTERM"), { code: 0, signal: null })
    server = await startServer(script, args, env)
    assert.deepEqual(await server.get("/api/board"), { repo: repoOf(repo), cards: [done] })
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

test("kanban: every request needs the token the server printed, as a bearer token or as the cookie its URL sets", { skip }, async () => {
  const repo = makeRepo()
  const state = mkState()
  const args = ["--repo", repo, "--agents", "codex", "--state", state]
  let server
  try {
    server = await startServer(script, args)
    assert.match(server.token, /^[0-9a-f]{64}$/)
    const status = async (path, init) => (await fetch(server.url + path, init)).status
    // Without the token, or with another, the API and the event stream say 401, and no card is made.
    const port = new URL(server.url).port
    const wrong = [{}, { authorization: "Bearer nope" }, { cookie: `supermux-kanban-${port}=${"0".repeat(64)}` }]
    for (const headers of wrong) {
      for (const path of ["/api/board", "/api/agents", "/api/events"]) assert.equal(await status(path, { headers }), 401, `${path} ${JSON.stringify(headers)}`)
      const create = await fetch(`${server.url}/api/cards`, { method: "POST", headers, body: JSON.stringify({ title: "t", prompt: "p", agent: "codex" }) })
      assert.deepEqual([create.status, (await create.json()).code], [401, "unauthorized"])
    }
    assert.deepEqual((await server.get("/api/board")).cards, [])
    for (const path of ["/", "/?token=nope"]) {
      const page = await fetch(server.url + path, { redirect: "manual" })
      assert.deepEqual([page.status, page.headers.get("set-cookie")], [401, null], path)
      assert.match(await page.text(), /Open the URL the server printed in its terminal/, path)
    }

    // The printed URL sets the cookie and sends the browser on to the page; the cookie then opens everything.
    const signIn = await fetch(`${server.url}/?token=${server.token}`, { redirect: "manual" })
    assert.deepEqual([signIn.status, signIn.headers.get("location")], [302, "/"])
    const cookie = signIn.headers.get("set-cookie")
    assert.equal(cookie, `supermux-kanban-${port}=${server.token}; HttpOnly; SameSite=Strict; Path=/`)
    const jar = { cookie: cookie.split(";")[0] }
    const page = await fetch(server.url + "/", { headers: jar })
    assert.equal(page.status, 200)
    assert.match(await page.text(), /id="board"/)
    assert.deepEqual(await (await fetch(`${server.url}/api/agents`, { headers: jar })).json(), ["codex"])
    const events = await openEvents(`${server.url}/api/events`, { headers: jar })
    assert.deepEqual(await events.next(m => m.type === "board"), { type: "board", repo: repoOf(repo), cards: [] })
    events.close()
    assert.equal(await status("/api/agents", { headers: { ...jar, origin: "http://evil.example" } }), 403)
    await server.stop()

    // --token keeps one token across starts; one too short to be a secret is refused.
    server = await startServer(script, [...args, "--token", "a-token-of-my-own-1234"])
    assert.equal(server.token, "a-token-of-my-own-1234")
    await server.stop()
    await assert.rejects(startServer(script, [...args, "--token", "short"]), /server exited with code 1\n[^]*--token takes 16 or more/)
  } finally {
    await server?.stop()
    rmSync(repo, { recursive: true, force: true })
    rmSync(state, { recursive: true, force: true })
  }
})
