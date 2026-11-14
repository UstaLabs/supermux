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
