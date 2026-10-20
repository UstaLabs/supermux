import { afterEach, expect, test } from "bun:test"
import { execFileSync, spawnSync } from "node:child_process"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

type Step = { name?: string; uses?: string; run?: string; with?: Record<string, unknown>; env?: Record<string, string> }
const workflow = Bun.YAML.parse(readFileSync(join(import.meta.dir, "..", "..", "..", ".github", "workflows", "publish-core.yml"), "utf8")) as {
  on: unknown
  jobs: { publish: { steps: Step[] } }
}
const steps = workflow.jobs.publish.steps
const named = (name: string): Step => {
  const step = steps.find(candidate => candidate.name === name)
  if (!step) throw new Error(`publish-core.yml has no step named ${name}`)
  return step
}

const dirs: string[] = []
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

test("the workflow runs for core-v* tags only", () => {
  expect(workflow.on).toEqual({ push: { tags: ["core-v*"] } })
})

test("trusted publishing: an id-token permission, no token secret, and an npm new enough for OIDC", () => {
  const raw = readFileSync(join(import.meta.dir, "..", "..", "..", ".github", "workflows", "publish-core.yml"), "utf8")
  expect((workflow as unknown as { permissions: Record<string, string> }).permissions).toEqual({ contents: "read", "id-token": "write" })
  expect(raw).not.toContain("NPM_TOKEN")
  expect(raw).not.toContain("NODE_AUTH_TOKEN")
  // npm 11.5.1+ is needed for trusted publishing; Node 24 ships it (Node 22 ships npm 10).
  const setupNode = steps.find(step => step.uses?.startsWith("actions/setup-node@"))!
  expect(setupNode.with!["node-version"]).toBe("24")
})

test("the checkout has all of history, and a tag off main is refused before anything is installed or published", () => {
  expect(steps[0]!.uses).toStartWith("actions/checkout@")
  expect(steps[0]!.with).toEqual({ "fetch-depth": 0 })
  // Right after the checkout, so a mis-cut tag costs nothing.
  expect(steps[1]).toBe(named("Tag is on main"))
})

test("the main guard passes a commit that is on origin/main and fails one that is not", () => {
  const repo = mkdtempSync(join(tmpdir(), "publish-workflow-"))
  dirs.push(repo)
  const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1", GIT_AUTHOR_NAME: "t", GIT_AUTHOR_EMAIL: "t@example.invalid", GIT_COMMITTER_NAME: "t", GIT_COMMITTER_EMAIL: "t@example.invalid" }
  const git = (...args: string[]) => execFileSync("git", args, { cwd: repo, env, encoding: "utf8" }).trim()
  git("init", "-q", "-b", "main")
  git("commit", "-q", "--allow-empty", "-m", "on main, tagged")
  const tagged = git("rev-parse", "HEAD")
  git("commit", "-q", "--allow-empty", "-m", "on main, later")
  // What actions/checkout with fetch-depth 0 leaves behind: origin/main is a branch of its own.
  git("update-ref", "refs/remotes/origin/main", "HEAD")
  git("checkout", "-q", "-b", "topic", tagged)
  git("commit", "-q", "--allow-empty", "-m", "only on a branch")
  const off = git("rev-parse", "HEAD")

  const guard = (sha: string) => spawnSync("bash", ["-e", "-c", named("Tag is on main").run!], { cwd: repo, env: { ...env, GITHUB_SHA: sha, GITHUB_REF_NAME: "core-v0.1.0" }, encoding: "utf8" })
  expect(guard(tagged).status).toBe(0)
  const refused = guard(off)
  expect(refused.status).toBe(1)
  expect(refused.stdout).toContain("::error::core-v0.1.0")
})

test("the package, then its alias, are published last, each by the script that skips what is published", () => {
  const publish = steps.slice(-2)
  expect(publish.map(step => step.name)).toEqual(["Publish @supermux/core", "Publish the supermux-core alias"])
  expect(publish.map(step => step.run)).toEqual(["bash scripts/publish-npm.sh packages/supermux-core", "bash scripts/publish-npm.sh packages/supermux-core-alias"])
  // Trusted publishing: no stored token anywhere; npm gets an OIDC token from the job's id-token permission.
  for (const step of publish) expect(step.env).toBeUndefined()
  // After the checks.
  expect(steps.indexOf(publish[0]!)).toBeGreaterThan(steps.indexOf(named("Node e2e")))
  expect(steps.findIndex(step => step.run === "bun test packages/supermux-core/tests")).toBeLessThan(steps.indexOf(publish[0]!))
})
