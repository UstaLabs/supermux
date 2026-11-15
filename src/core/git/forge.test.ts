// #72: a branch's pull request (GitHub) or merge request (GitLab): state, review, checks and comments, in one shape.
import { test, expect } from "bun:test"
import { forgeOf, parseGhPr, parseGlabMr, readPr, type Exec } from "./forge"

test("the forge comes from origin's host", () => {
  expect(forgeOf("git@github.com:acme/shop.git")).toBe("github")
  expect(forgeOf("https://github.example.com/acme/shop.git")).toBe("github")
  expect(forgeOf("git@gitlab.com:acme/shop.git")).toBe("gitlab")
  expect(forgeOf("https://gitlab.acme.io/team/sub/shop.git")).toBe("gitlab")
  expect(forgeOf("")).toBeNull()
})

const ghView = {
  number: 12, url: "https://github.com/acme/shop/pull/12", title: "Add a search box", state: "OPEN", isDraft: false,
  baseRefName: "dev", headRefName: "mux/search", reviewDecision: "CHANGES_REQUESTED",
  latestReviews: [{ author: { login: "deniz" }, state: "CHANGES_REQUESTED" }],
  statusCheckRollup: [
    { __typename: "CheckRun", name: "build", workflowName: "CI", status: "COMPLETED", conclusion: "SUCCESS", detailsUrl: "https://x/1" },
    { __typename: "CheckRun", name: "unit tests", status: "COMPLETED", conclusion: "FAILURE", detailsUrl: "https://x/2" },
    { __typename: "CheckRun", name: "lint", status: "IN_PROGRESS", conclusion: "", detailsUrl: "https://x/3" },
    { __typename: "CheckRun", name: "docs", status: "COMPLETED", conclusion: "SKIPPED", detailsUrl: "" },
    { __typename: "StatusContext", context: "ci/legacy", state: "PENDING", targetUrl: "https://x/4" },
  ],
  comments: [{ author: { login: "ahmet" }, body: "Looks good otherwise", url: "https://x/c1" }],
}
const ghInline = [
  { user: { login: "deniz" }, path: "src/App.jsx", line: 17, body: "Can we debounce this?", html_url: "https://x/r1" },
  { user: { login: "ahmet" }, path: "src/App.jsx", line: 17, body: "Sure", html_url: "https://x/r2", in_reply_to_id: 1 },
]

test("GitHub: state, review, every check kind and both comment kinds", () => {
  const pr = parseGhPr(ghView, ghInline)
  expect(pr).toMatchObject({ forge: "github", number: 12, url: ghView.url, title: "Add a search box", state: "open", draft: false, target: "dev", head: "mux/search" })
  expect(pr.review).toEqual({ state: "changes", by: ["deniz"] })
  expect(pr.checks).toEqual([
    { name: "CI / build", state: "ok", url: "https://x/1" },
    { name: "unit tests", state: "bad", url: "https://x/2" },
    { name: "lint", state: "run", url: "https://x/3" },
    { name: "docs", state: "skip" },
    { name: "ci/legacy", state: "run", url: "https://x/4" },
  ])
  // replies sit under their thread on GitHub; only the thread's first comment is listed
  expect(pr.comments).toEqual([
    { who: "deniz", body: "Can we debounce this?", file: "src/App.jsx", line: 17, url: "https://x/r1" },
    { who: "ahmet", body: "Looks good otherwise", url: "https://x/c1" },
  ])
})

test("GitHub: a check run twice on one commit (push and pull_request) is shown once, newest first", () => {
  const roll = [
    { __typename: "CheckRun", name: "ts", workflowName: "ci", status: "COMPLETED", conclusion: "FAILURE", startedAt: "2026-11-14T10:00:00Z" },
    { __typename: "CheckRun", name: "ts", workflowName: "ci", status: "IN_PROGRESS", conclusion: "", startedAt: "2026-11-14T10:05:00Z" },
  ]
  expect(parseGhPr({ ...ghView, statusCheckRollup: roll }, []).checks).toEqual([{ name: "ci / ts", state: "run" }])
})

test("GitHub: merged, draft and no decision read right", () => {
  expect(parseGhPr({ ...ghView, state: "MERGED", reviewDecision: "APPROVED", latestReviews: [{ author: { login: "deniz" }, state: "APPROVED" }] }, []).review).toEqual({ state: "approved", by: ["deniz"] })
  expect(parseGhPr({ ...ghView, state: "MERGED" }, []).state).toBe("merged")
  expect(parseGhPr({ ...ghView, isDraft: true, reviewDecision: "REVIEW_REQUIRED", latestReviews: [] }, [])).toMatchObject({ draft: true, review: { state: "required", by: [] } })
  expect(parseGhPr({ ...ghView, reviewDecision: "", latestReviews: [], statusCheckRollup: null }, [])).toMatchObject({ review: { state: "none", by: [] }, checks: [] })
})

const glMr = {
  iid: 7, web_url: "https://gitlab.com/acme/shop/-/merge_requests/7", title: "Draft: Add a search box", state: "opened", draft: true,
  target_branch: "dev", source_branch: "mux/search", head_pipeline: { id: 99, status: "failed" },
}
const glApprovals = { approved: false, approvals_required: 2, approvals_left: 1, approved_by: [{ user: { username: "deniz" } }] }
const glJobs = [
  { name: "build", status: "success", web_url: "https://g/1" },
  { name: "test", status: "failed", web_url: "https://g/2" },
  { name: "deploy", status: "manual", web_url: "https://g/3" },
  { name: "lint", status: "running", web_url: "https://g/4" },
]
const glDiscussions = [
  { notes: [{ system: true, author: { username: "bot" }, body: "added 1 commit" }] },
  { notes: [{ system: false, author: { username: "deniz" }, body: "Debounce?", position: { new_path: "src/App.jsx", new_line: 17 } }, { system: false, author: { username: "ahmet" }, body: "ok" }] },
  { notes: [{ system: false, author: { username: "can" }, body: "Nice" }] },
]

test("GitLab: approvals as N of M, pipeline jobs as checks, discussions as comments", () => {
  const mr = parseGlabMr(glMr, glApprovals, glJobs, glDiscussions)
  expect(mr).toMatchObject({ forge: "gitlab", number: 7, url: glMr.web_url, title: "Draft: Add a search box", state: "open", draft: true, target: "dev", head: "mux/search" })
  expect(mr.review).toEqual({ state: "required", by: ["deniz"], got: 1, need: 2 })
  expect(mr.checks).toEqual([
    { name: "build", state: "ok", url: "https://g/1" },
    { name: "test", state: "bad", url: "https://g/2" },
    { name: "deploy", state: "skip", url: "https://g/3" },
    { name: "lint", state: "run", url: "https://g/4" },
  ])
  expect(mr.comments).toEqual([
    { who: "deniz", body: "Debounce?", file: "src/App.jsx", line: 17 },
    { who: "can", body: "Nice" },
  ])
  expect(parseGlabMr({ ...glMr, state: "merged" }, { ...glApprovals, approved: true, approvals_left: 0 }, [], []).review.state).toBe("approved")
})

/** A fake CLI: answers by the first args, records every call. */
function fake(answers: Array<[RegExp, { ok: boolean; out: string }]>): Exec & { calls: string[] } {
  const calls: string[] = []
  const f = (async (cmd: string, args: string[]) => {
    const line = [cmd, ...args].join(" ")
    calls.push(line)
    for (const [re, a] of answers) if (re.test(line)) return a
    return { ok: false, out: `unexpected: ${line}` }
  }) as unknown as Exec & { calls: string[] }
  f.calls = calls
  return f
}

test("readPr asks gh for the branch's PR and its inline comments", async () => {
  const ex = fake([
    [/^git .*remote get-url origin/, { ok: true, out: "git@github.com:acme/shop.git" }],
    [/^gh pr view mux\/search --json/, { ok: true, out: JSON.stringify(ghView) }],
    [/^gh api repos\/\{owner\}\/\{repo\}\/pulls\/12\/comments/, { ok: true, out: JSON.stringify(ghInline) }],
  ])
  const r = await readPr("/repo", "mux/search", ex)
  expect(r.status).toBe("found")
  if (r.status === "found") expect(r.pr.number).toBe(12)
})

test("no PR for the branch, no CLI, and not signed in are told apart", async () => {
  const none = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr view/, { ok: false, out: 'no pull requests found for branch "x"' }]])
  expect(await readPr("/r", "x", none)).toEqual({ status: "none", forge: "github" })
  const nocli = fake([[/remote get-url/, { ok: true, out: "git@gitlab.com:a/b.git" }], [/^glab mr view/, { ok: false, out: "spawn glab ENOENT" }]])
  expect(await readPr("/r", "x", nocli)).toMatchObject({ status: "unavailable", forge: "gitlab", reason: "no_cli" })
  const auth = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr view/, { ok: false, out: "To get started with GitHub CLI, please run:  gh auth login" }]])
  expect(await readPr("/r", "x", auth)).toMatchObject({ status: "unavailable", reason: "not_authed" })
  const glNone = fake([[/remote get-url/, { ok: true, out: "git@gitlab.com:a/b.git" }], [/^glab mr view/, { ok: false, out: "no open merge request available for \"x\"" }]])
  expect(await readPr("/r", "x", glNone)).toEqual({ status: "none", forge: "gitlab" })
  const noRemote = fake([[/remote get-url/, { ok: false, out: "error: No such remote 'origin'" }]])
  expect(await readPr("/r", "x", noRemote)).toMatchObject({ status: "unavailable", reason: "no_remote" })
})

test("readPr on GitLab reads the MR, its approvals, its pipeline's jobs and its discussions", async () => {
  const ex = fake([
    [/remote get-url/, { ok: true, out: "git@gitlab.com:acme/shop.git" }],
    [/^glab mr view mux\/search -F json/, { ok: true, out: JSON.stringify(glMr) }],
    [/^glab api projects\/:id\/merge_requests\/7\/approvals/, { ok: true, out: JSON.stringify(glApprovals) }],
    [/^glab api projects\/:id\/pipelines\/99\/jobs/, { ok: true, out: JSON.stringify(glJobs) }],
    [/^glab api projects\/:id\/merge_requests\/7\/discussions/, { ok: true, out: JSON.stringify(glDiscussions) }],
  ])
  const r = await readPr("/repo", "mux/search", ex)
  expect(r.status).toBe("found")
  if (r.status === "found") expect(r.pr).toMatchObject({ forge: "gitlab", number: 7, review: { got: 1, need: 2 } })
})

// ---- writes: create, ready, a failing check's log ----
import { checkLog, createPr, markReady } from "./forge"

test("create: gh with base, title, body and draft; glab with source, target and --yes", async () => {
  const gh = fake([[/remote get-url/, { ok: true, out: "git@github.com:acme/shop.git" }], [/^gh pr create/, { ok: true, out: "https://github.com/acme/shop/pull/13\n" }]])
  expect(await createPr("/r", "mux/search", { title: "T", body: "B", target: "dev", draft: true }, gh)).toEqual({ status: "opened", url: "https://github.com/acme/shop/pull/13", number: 13 })
  expect(gh.calls[1]).toBe("gh pr create --head mux/search --base dev --title T --body B --draft")
  const gl = fake([[/remote get-url/, { ok: true, out: "git@gitlab.com:acme/shop.git" }], [/^glab mr create/, { ok: true, out: "!8 T (mux/search)\n https://gitlab.com/acme/shop/-/merge_requests/8\n" }]])
  expect(await createPr("/r", "mux/search", { title: "T", body: "", target: "dev", draft: false }, gl)).toEqual({ status: "opened", url: "https://gitlab.com/acme/shop/-/merge_requests/8", number: 8 })
  expect(gl.calls[1]).toBe("glab mr create --source-branch mux/search --target-branch dev --title T --description  --yes")
})

test("create: one that already exists, and no CLI, are said plainly", async () => {
  const dup = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr create/, { ok: false, out: 'a pull request for branch "x" into branch "dev" already exists:\nhttps://github.com/a/b/pull/5' }]])
  expect(await createPr("/r", "x", { title: "T", body: "", target: "dev", draft: false }, dup)).toEqual({ status: "exists", url: "https://github.com/a/b/pull/5" })
  const nocli = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr create/, { ok: false, out: "spawn gh ENOENT" }]])
  expect(await createPr("/r", "x", { title: "T", body: "", target: "dev", draft: false }, nocli)).toMatchObject({ status: "unavailable", reason: "no_cli" })
})

test("ready: gh pr ready N / glab mr update N --ready", async () => {
  const gh = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr ready 12$/, { ok: true, out: "" }]])
  expect(await markReady("/r", 12, gh)).toEqual({ ok: true })
  const gl = fake([[/remote get-url/, { ok: true, out: "git@gitlab.com:a/b.git" }], [/^glab mr update 7 --ready$/, { ok: true, out: "" }]])
  expect(await markReady("/r", 7, gl)).toEqual({ ok: true })
})

test("a failing check's log: GitHub job from its URL, GitLab job trace; the tail only", async () => {
  const big = "x".repeat(50_000) + "\nERROR: expected 3, got 2"
  const gh = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh run view --job 132519958502 --log-failed$/, { ok: true, out: big }]])
  const r = await checkLog("/r", "https://github.com/a/b/actions/runs/43618918392/job/132519958502", gh)
  expect(r.ok && r.log.endsWith("ERROR: expected 3, got 2") && r.log.length <= 20_000).toBe(true)
  const gl = fake([[/remote get-url/, { ok: true, out: "git@gitlab.com:a/b.git" }], [/^glab api projects\/:id\/jobs\/555\/trace$/, { ok: true, out: "boom" }]])
  expect(await checkLog("/r", "https://gitlab.com/a/b/-/jobs/555", gl)).toEqual({ ok: true, log: "boom" })
  expect(await checkLog("/r", "https://elsewhere.example/ci/1", gh)).toEqual({ ok: false, reason: "unknown_check" })
})


// ---- #72 G10: publishing a project that has no remote yet (VS Code's "Publish to GitHub") ----
import { addRemote, prBase, publishNew } from "./forge"

test("add a remote: a name and a URL git accepts; a taken name is said", async () => {
  const ok = fake([[/^git -C \/r remote add origin git@github.com:a\/b.git$/, { ok: true, out: "" }]])
  expect(await addRemote("/r", "origin", "git@github.com:a/b.git", ok)).toEqual({ ok: true })
  const taken = fake([[/^git -C \/r remote add/, { ok: false, out: "error: remote origin already exists." }]])
  expect(await addRemote("/r", "origin", "x", taken)).toEqual({ ok: false, reason: "exists" })
  expect(await addRemote("/r", "bad name", "x", ok)).toEqual({ ok: false, reason: "bad" })
  expect(await addRemote("/r", "origin", "", ok)).toEqual({ ok: false, reason: "bad" })
})

test("publish to GitHub: gh creates an empty repo (never --source: it refuses a worktree), then origin is added and pushed", async () => {
  const ex = fake([
    [/^gh repo create shop --private$/, { ok: true, out: "https://github.com/acme/shop\n" }],
    [/^gh config get git_protocol -h github.com$/, { ok: true, out: "https\n" }],
    [/^git -C \/r remote add origin https:\/\/github.com\/acme\/shop.git$/, { ok: true, out: "" }],
    [/^git -C \/r push -u origin HEAD$/, { ok: true, out: "" }],
  ])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "private" }, ex)).toEqual({ ok: true, url: "https://github.com/acme/shop" })
  const ssh = fake([
    [/^gh repo create shop --public$/, { ok: true, out: "https://github.com/acme/shop\n" }],
    [/^gh config get git_protocol/, { ok: true, out: "ssh\n" }],
    [/^git -C \/r remote add origin git@github.com:acme\/shop.git$/, { ok: true, out: "" }],
    [/^git -C \/r push -u origin HEAD$/, { ok: false, out: "Permission denied (publickey)." }],
  ])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "public" }, ssh)).toMatchObject({ ok: false, reason: "error", message: expect.stringContaining("Created https://github.com/acme/shop and added it as origin, but the push failed: Permission denied") })
  const nocli = fake([[/^gh repo create/, { ok: false, out: "spawn gh ENOENT" }]])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "public" }, nocli)).toMatchObject({ ok: false, reason: "no_cli" })
})

test("publish to GitLab: glab creates it, then origin is added and the branch pushed", async () => {
  const ex = fake([
    [/^glab repo create shop --private --defaultBranch/, { ok: true, out: "✓ Created repository acme/shop on GitLab: https://gitlab.com/acme/shop\n" }],
    [/^git -C \/r remote add origin https:\/\/gitlab.com\/acme\/shop.git$/, { ok: true, out: "" }],
    [/^git -C \/r push -u origin HEAD$/, { ok: true, out: "" }],
  ])
  expect(await publishNew("/r", { forge: "gitlab", name: "shop", visibility: "private", branch: "main" }, ex)).toEqual({ ok: true, url: "https://gitlab.com/acme/shop" })
})

test("publish from a chat's branch puts its base on first, so the new repo's default is the base", async () => {
  const ex = fake([
    [/^git check-ref-format --branch main$/, { ok: true, out: "main" }],
    [/^git -C \/r rev-parse --verify --quiet refs\/heads\/main$/, { ok: true, out: "abc" }],
    [/^gh repo create shop --private$/, { ok: true, out: "https://github.com/acme/shop\n" }],
    [/^gh config get git_protocol/, { ok: true, out: "https" }],
    [/^git -C \/r remote add origin/, { ok: true, out: "" }],
    [/^git -C \/r push -u origin refs\/heads\/main:refs\/heads\/main$/, { ok: true, out: "" }],
    [/^git -C \/r push -u origin HEAD$/, { ok: true, out: "" }],
  ])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "private", branch: "fix-search", base: "main" }, ex)).toEqual({ ok: true, url: "https://github.com/acme/shop", base: "main" })
  const pushes = ex.calls.filter((c) => c.includes(" push "))
  expect(pushes).toEqual(["git -C /r push -u origin refs/heads/main:refs/heads/main", "git -C /r push -u origin HEAD"])
  // On the base itself (or none): just the branch.
  const one = fake([[/^gh repo create/, { ok: true, out: "https://github.com/acme/shop" }], [/^gh config/, { ok: true, out: "https" }], [/^git -C \/r (remote add|push -u origin HEAD)/, { ok: true, out: "" }]])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "private", branch: "main", base: "main" }, one)).toEqual({ ok: true, url: "https://github.com/acme/shop" })
  const taken = fake([[/^gh repo create/, { ok: false, out: "GraphQL: Name already exists on this account (createRepository)" }]])
  expect(await publishNew("/r", { forge: "github", name: "shop", visibility: "private" }, taken)).toMatchObject({ ok: false, reason: "name_taken" })
})

test("a PR the forge refuses is said by its reason: nothing to propose, or a target it doesn't have", async () => {
  const gh = (out: string) => fake([[/^git -C \/r remote get-url origin$/, { ok: true, out: "https://github.com/acme/shop.git" }], [/^gh pr create/, { ok: false, out }]])
  const o = { title: "t", body: "", target: "main", draft: false }
  expect(await createPr("/r", "fix", o, gh("pull request create failed: GraphQL: Head sha can't be blank, Base sha can't be blank, No commits between main and fix, Base ref must be a branch (createPullRequest)")))
    .toMatchObject({ status: "unavailable", reason: "base_missing" })
  expect(await createPr("/r", "fix", o, gh("pull request create failed: GraphQL: No commits between main and fix (createPullRequest)"))).toMatchObject({ status: "unavailable", reason: "no_commits" })
})

test("can a PR into the target be opened: on origin or only here, and how far ahead", async () => {
  const sha = "a".repeat(40)
  const onOrigin = fake([
    [/^git check-ref-format --branch main$/, { ok: true, out: "main" }],
    [/^git -C \/r ls-remote --heads origin refs\/heads\/main$/, { ok: true, out: `${sha}\trefs/heads/main` }],
    [/^git -C \/r rev-parse --verify --quiet refs\/heads\/main$/, { ok: true, out: sha }],
    [/^git -C \/r cat-file -e/, { ok: true, out: "" }],
    [/^git -C \/r rev-list --count a+\.\.HEAD$/, { ok: true, out: "2" }],
  ])
  expect(await prBase("/r", "main", onOrigin)).toEqual({ target: "main", onRemote: true, local: true, ahead: 2 })
  const onlyHere = fake([
    [/^git check-ref-format/, { ok: true, out: "main" }],
    [/^git -C \/r ls-remote/, { ok: true, out: "" }],
    [/^git -C \/r rev-parse --verify --quiet refs\/heads\/main$/, { ok: true, out: "b".repeat(40) }],
    [/^git -C \/r rev-list --count/, { ok: true, out: "1" }],
  ])
  expect(await prBase("/r", "main", onlyHere)).toEqual({ target: "main", onRemote: false, local: true, ahead: 1 })
  expect(await prBase("/r", "-x", onlyHere)).toBeNull()
})

test("no target (a chat in a folder): origin's own default branch", async () => {
  const ex = fake([
    [/^git -C \/r ls-remote --symref origin HEAD$/, { ok: true, out: "ref: refs/heads/trunk\tHEAD\n" + "c".repeat(40) + "\tHEAD" }],
    [/^git check-ref-format --branch trunk$/, { ok: true, out: "trunk" }],
    [/^git -C \/r ls-remote --heads origin refs\/heads\/trunk$/, { ok: true, out: "c".repeat(40) + "\trefs/heads/trunk" }],
    [/^git -C \/r rev-parse --verify --quiet refs\/heads\/trunk$/, { ok: false, out: "" }],
    [/^git -C \/r cat-file -e/, { ok: true, out: "" }],
    [/^git -C \/r rev-list --count/, { ok: true, out: "0" }],
  ])
  expect(await prBase("/r", "", ex)).toEqual({ target: "trunk", onRemote: true, local: false, ahead: 0 })
})
