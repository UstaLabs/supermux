// #152: the launcher's Pull request tab. Open PRs/MRs listed or read by number or URL, each with its own head branch,
// whether it comes from a fork, whether the author lets maintainers push to it, and its checks, from gh / glab.
import { test, expect } from "bun:test"
import { listOpenPrs, parsePrRef, readPrByNumber, type Exec } from "./forge"

function fake(rules: Array<[RegExp, { ok: boolean; out: string }]>) {
  const calls: string[] = []
  const f = (async (cmd: string, args: string[]) => {
    const line = [cmd, ...args].join(" ")
    calls.push(line)
    for (const [re, r] of rules) if (re.test(line)) return r
    return { ok: false, out: `unexpected: ${line}` }
  }) as unknown as Exec & { calls: string[] }
  f.calls = calls
  return f
}

const ghSame = {
  number: 123, title: "Fix login redirect loop", url: "https://github.com/acme/storefront/pull/123", state: "OPEN",
  headRefName: "feat/login-redirect", headRepository: { name: "storefront" }, headRepositoryOwner: { login: "acme" },
  isCrossRepository: false, maintainerCanModify: false, baseRefName: "main", isDraft: false, author: { login: "deniz" },
  statusCheckRollup: [{ __typename: "CheckRun", name: "ci", status: "COMPLETED", conclusion: "SUCCESS" }],
}
const ghFork = {
  ...ghSame, number: 121, title: "Bump payments SDK to 4.2", url: "https://github.com/acme/storefront/pull/121",
  headRefName: "deps/sdk-4.2", headRepository: { name: "storefront" }, headRepositoryOwner: { login: "ozan" },
  isCrossRepository: true, maintainerCanModify: true, author: { login: "ozan" },
  statusCheckRollup: [{ __typename: "CheckRun", name: "ci", status: "IN_PROGRESS", conclusion: "" }],
}
const ghForkRo = { ...ghFork, number: 109, headRefName: "i18n-tr", headRepositoryOwner: { login: "selin" }, maintainerCanModify: false, isDraft: true, statusCheckRollup: [{ __typename: "CheckRun", name: "ci", status: "COMPLETED", conclusion: "FAILURE" }] }

test("a PR is named by #N, !N, N or its URL; anything else is not one", () => {
  expect(parsePrRef("#123")).toEqual({ number: 123 })
  expect(parsePrRef("!7")).toEqual({ number: 7 })
  expect(parsePrRef(" 42 ")).toEqual({ number: 42 })
  expect(parsePrRef("https://github.com/acme/storefront/pull/121")).toEqual({ number: 121, repo: "acme/storefront" })
  expect(parsePrRef("https://gitlab.com/team/sub/app/-/merge_requests/9")).toEqual({ number: 9, repo: "team/sub/app" })
  expect(parsePrRef("feat/x")).toBeNull()
  expect(parsePrRef("#0")).toBeNull()
})

test("GitHub: the open PRs with their head, fork, maintainer edits, draft and checks", async () => {
  const ex = fake([
    [/^git .*remote get-url origin/, { ok: true, out: "git@github.com:acme/storefront.git" }],
    [/^gh pr list --state open --limit 50 --json /, { ok: true, out: JSON.stringify([ghSame, ghFork, ghForkRo]) }],
    [/^git .*rev-parse --verify --quiet refs\/heads\/feat\/login-redirect/, { ok: true, out: "aaa" }],
    [/^git .*rev-list --count refs\/remotes\/origin\/feat\/login-redirect\.\.refs\/heads\/feat\/login-redirect/, { ok: true, out: "2" }],
    [/^git .*rev-parse --verify --quiet refs\/heads\//, { ok: false, out: "" }],
  ])
  const r = await listOpenPrs("/repo", "", ex)
  expect(r.status).toBe("ok")
  if (r.status !== "ok") return
  expect(r.repo).toBe("github.com/acme/storefront")
  expect(r.prs.map((p) => p.number)).toEqual([123, 121, 109])
  expect(r.prs[0]).toEqual({
    forge: "github", number: 123, title: "Fix login redirect loop", url: ghSame.url, author: "deniz", base: "main", head: "feat/login-redirect",
    headRepo: "acme/storefront", isFork: false, maintainerCanModify: false, draft: false, checks: "ok", localAhead: 2,
  })
  expect(r.prs[1]).toMatchObject({ isFork: true, headRepo: "ozan/storefront", headOwner: "ozan", headUrl: "git@github.com:ozan/storefront.git", maintainerCanModify: true, checks: "run" })
  expect(r.prs[2]).toMatchObject({ isFork: true, maintainerCanModify: false, draft: true, checks: "bad" })
})

test("GitHub: a search goes to gh, and a fork's URL follows origin's scheme", async () => {
  const ex = fake([
    [/^git .*remote get-url origin/, { ok: true, out: "https://github.com/acme/storefront.git" }],
    [/^gh pr list --state open --limit 50 --json .* --search sdk$/, { ok: true, out: JSON.stringify([ghFork]) }],
    [/^git .*rev-parse/, { ok: false, out: "" }],
  ])
  const r = await listOpenPrs("/repo", "sdk", ex)
  expect(r.status === "ok" && r.prs[0]!.headUrl).toBe("https://github.com/ozan/storefront.git")
})

test("GitHub: one PR by number or URL; another repo's URL and a closed PR are refused", async () => {
  const ex = fake([
    [/^git .*remote get-url origin/, { ok: true, out: "git@github.com:acme/storefront.git" }],
    [/^gh pr view 121 --json /, { ok: true, out: JSON.stringify(ghFork) }],
    [/^gh pr view 5 --json /, { ok: true, out: JSON.stringify({ ...ghSame, number: 5, state: "MERGED" }) }],
    [/^gh pr view 404 --json /, { ok: false, out: "GraphQL: Could not resolve to a PullRequest with the number of 404." }],
    [/^git .*rev-parse/, { ok: false, out: "" }],
  ])
  expect(await readPrByNumber("/repo", "https://github.com/acme/storefront/pull/121", ex)).toMatchObject({ status: "found", pr: { number: 121, isFork: true } })
  expect(await readPrByNumber("/repo", "#121", ex)).toMatchObject({ status: "found", pr: { number: 121 } })
  expect(await readPrByNumber("/repo", "https://github.com/other/repo/pull/121", ex)).toEqual({ status: "other_repo", forge: "github", repo: "other/repo" })
  expect(await readPrByNumber("/repo", "#5", ex)).toEqual({ status: "not_open", forge: "github", number: 5, state: "merged" })
  expect(await readPrByNumber("/repo", "#404", ex)).toEqual({ status: "none", forge: "github" })
  expect(await readPrByNumber("/repo", "feat/x", ex)).toEqual({ status: "bad_ref" })
})

const glSame = {
  iid: 7, title: "Speed up product search", web_url: "https://gitlab.com/acme/storefront/-/merge_requests/7", state: "opened",
  source_branch: "feat/search-speed", target_branch: "main", draft: false, author: { username: "deniz" },
  source_project_id: 10, target_project_id: 10, allow_collaboration: false, head_pipeline: { status: "success" },
}
const glFork = { ...glSame, iid: 9, source_branch: "i18n-tr", source_project_id: 33, allow_collaboration: true, head_pipeline: { status: "running" }, author: { username: "selin" } }

test("GitLab: merge requests, a fork's project read for its owner and URL", async () => {
  const ex = fake([
    [/^git .*remote get-url origin/, { ok: true, out: "git@gitlab.com:acme/storefront.git" }],
    [/^glab mr list -F json --per-page 50$/, { ok: true, out: JSON.stringify([glSame, glFork]) }],
    [/^glab api projects\/33$/, { ok: true, out: JSON.stringify({ path_with_namespace: "selin/storefront", namespace: { path: "selin" }, ssh_url_to_repo: "git@gitlab.com:selin/storefront.git", http_url_to_repo: "https://gitlab.com/selin/storefront.git" }) }],
    [/^git .*rev-parse/, { ok: false, out: "" }],
  ])
  const r = await listOpenPrs("/repo", "", ex)
  expect(r.status).toBe("ok")
  if (r.status !== "ok") return
  expect(r.prs[0]).toMatchObject({ forge: "gitlab", number: 7, head: "feat/search-speed", isFork: false, checks: "ok", author: "deniz" })
  expect(r.prs[1]).toMatchObject({ number: 9, isFork: true, headRepo: "selin/storefront", headOwner: "selin", headUrl: "git@gitlab.com:selin/storefront.git", maintainerCanModify: true, checks: "run" })
})

test("no remote, no CLI and not signed in are said plainly", async () => {
  expect(await listOpenPrs("/r", "", fake([[/remote get-url/, { ok: false, out: "error: No such remote 'origin'" }]]))).toMatchObject({ status: "unavailable", reason: "no_remote" })
  const nocli = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr list/, { ok: false, out: "spawn gh ENOENT" }]])
  expect(await listOpenPrs("/r", "", nocli)).toMatchObject({ status: "unavailable", forge: "github", reason: "no_cli" })
  const auth = fake([[/remote get-url/, { ok: true, out: "git@github.com:a/b.git" }], [/^gh pr view/, { ok: false, out: "To get started with GitHub CLI, please run:  gh auth login" }]])
  expect(await readPrByNumber("/r", "#3", auth)).toMatchObject({ status: "unavailable", reason: "not_authed" })
})
