import { afterEach, expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

type Step = { name?: string; uses?: string; run?: string; with?: Record<string, unknown>; env?: Record<string, string> }
type Job = { if?: string; needs?: string[]; permissions?: Record<string, string>; steps: Step[] }
const repoRoot = join(import.meta.dir, "..", "..", "..")
const raw = readFileSync(join(repoRoot, ".github", "workflows", "release.yml"), "utf8")
const workflow = Bun.YAML.parse(raw) as { on: { push: { tags: string[] } }; jobs: Record<string, Job> }
const job = workflow.jobs["publish-core"]!
const steps = job.steps
const named = (name: string): Step => {
  const step = steps.find(candidate => candidate.name === name)
  if (!step) throw new Error(`release.yml's publish-core job has no step named ${name}`)
  return step
}

const dirs: string[] = []
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

test("the core is published by the release workflow, on every v* tag, only once the release itself is out", () => {
  expect(workflow.on.push.tags).toEqual(["v*"])
  expect(job.if).toBe("github.ref_type == 'tag'")
  // classify refuses a tag cut from the wrong branch; release needs every build and verification lane.
  expect(job.needs).toEqual(["classify", "release"])
})

test("trusted publishing: an id-token permission, no token secret, and an npm new enough for OIDC", () => {
  expect(job.permissions).toEqual({ contents: "read", "id-token": "write" })
  expect(raw).not.toContain("NPM_TOKEN")
  expect(raw).not.toContain("NODE_AUTH_TOKEN")
  // npm 11.5.1+ is needed for trusted publishing; Node 24 ships it (Node 22 ships npm 10).
  const setupNode = steps.find(step => step.uses?.startsWith("actions/setup-node@"))!
  expect(setupNode.with!["node-version"]).toBe("24")
})

test("the tag's version is stamped after the install and before every check", () => {
  const stamp = named("Stamp the tag's version")
  expect(stamp.run).toBe(`node scripts/set-core-version.mjs "\${GITHUB_REF_NAME#v}"`)
  expect(steps.indexOf(stamp)).toBeGreaterThan(steps.findIndex(step => step.run === "bun install --frozen-lockfile"))
  expect(steps.indexOf(stamp)).toBeLessThan(steps.findIndex(step => step.run === "bun run typecheck:core"))
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

/** A copy of the files set-core-version.mjs edits, so the test never touches the real ones. */
function copyOfPackages(): string {
  const root = mkdtempSync(join(tmpdir(), "set-core-version-"))
  dirs.push(root)
  const files = [
    "supermux-core/package.json",
    "supermux-core-alias/package.json",
    "supermux-core/src/version.ts",
    "supermux-core/examples/chat/package.json",
    "supermux-core/examples/kanban/package.json",
    "supermux-core/examples/recipes/package.json",
  ]
  for (const file of files) {
    mkdirSync(join(root, "packages", file, ".."), { recursive: true })
    cpSync(join(repoRoot, "packages", file), join(root, "packages", file))
  }
  return root
}
const setVersion = (version: string, root: string) =>
  // process.execPath: Bun under `bun test`; the script is plain Node and runs in either.
  spawnSync(process.execPath, [join(repoRoot, "scripts", "set-core-version.mjs"), version, root], { encoding: "utf8" })
const readJson = (root: string, path: string) => JSON.parse(readFileSync(join(root, "packages", path), "utf8"))

test("set-core-version writes one version into the core, the alias, LIBRARY_VERSION and the examples", () => {
  const root = copyOfPackages()
  expect(setVersion("0.15.0-alpha.2", root).status).toBe(0)
  expect(readJson(root, "supermux-core/package.json").version).toBe("0.15.0-alpha.2")
  const alias = readJson(root, "supermux-core-alias/package.json")
  expect(alias.version).toBe("0.15.0-alpha.2")
  expect(alias.dependencies).toEqual({ "@supermux/core": "0.15.0-alpha.2" })
  expect(readFileSync(join(root, "packages", "supermux-core/src/version.ts"), "utf8")).toContain(`export const LIBRARY_VERSION = "0.15.0-alpha.2"`)
  for (const example of ["chat", "kanban", "recipes"]) {
    expect(readJson(root, `supermux-core/examples/${example}/package.json`).dependencies["@supermux/core"]).toBe("^0.15.0-alpha.2")
  }
  // Everything else in the package is left as it was.
  const before = JSON.parse(readFileSync(join(repoRoot, "packages", "supermux-core/package.json"), "utf8"))
  expect({ ...readJson(root, "supermux-core/package.json"), version: before.version }).toEqual(before)
})

test("set-core-version refuses what is not a version, and changes nothing", () => {
  const root = copyOfPackages()
  for (const bad of ["", "v0.14.0", "0.14", "latest"]) {
    const run = setVersion(bad, root)
    expect(run.status).toBe(2)
  }
  expect(readJson(root, "supermux-core/package.json").version).toBe(JSON.parse(readFileSync(join(repoRoot, "packages", "supermux-core/package.json"), "utf8")).version)
})
