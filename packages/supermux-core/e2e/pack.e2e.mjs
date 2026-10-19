import { test } from "node:test"
import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
import { readFileSync, statSync } from "node:fs"
import { delimiter, dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

// `npm pack --dry-run` lists what `npm publish` would upload, without writing a tarball or contacting the registry.
// It runs with --ignore-scripts: this package's `prepack` deletes dist, which the other e2e files, running in
// parallel processes, import.

const packages = new URL("../../", import.meta.url)
const folder = name => fileURLToPath(new URL(`${name}/`, packages))

// npm starts with `#!/usr/bin/env node`, so this node's directory goes first on PATH: a node that is not on PATH
// itself (an install the shell does not know about) would otherwise leave npm with no node to run on.
const env = { ...process.env, PATH: `${dirname(process.execPath)}${delimiter}${process.env.PATH ?? ""}`, npm_config_update_notifier: "false" }
const npm = [join(dirname(process.execPath), "npm"), "npm"].find(candidate => spawnSync(candidate, ["--version"], { env, stdio: "ignore" }).status === 0)

const noNpm = "no working npm beside this node or on PATH"
// Under CI a missing npm fails the tests: a check that skips there stops guarding the publish without anyone noticing.
const skip = !npm && !process.env.CI && noNpm

/** The package name and the file paths `npm pack` would put in the tarball of packages/<directory>. */
function pack(directory) {
  assert.ok(npm, noNpm)
  const run = spawnSync(npm, ["pack", "--dry-run", "--json", "--ignore-scripts"], { cwd: folder(directory), env, encoding: "utf8" })
  assert.equal(run.status, 0, `npm pack failed in packages/${directory}: ${run.error ?? run.stderr}`)
  const reports = JSON.parse(run.stdout)
  assert.equal(reports.length, 1, `npm pack reported ${reports.length} packages for packages/${directory}`)
  return { name: reports[0].name, files: reports[0].files.map(file => file.path) }
}

/** `packages/<directory>` is the package `name`, ships every path in `has`, and ships nothing under a `hasNo` prefix. */
function check(directory, { name, has, hasNo = [] }) {
  const packed = pack(directory)
  assert.equal(packed.name, name)
  assert.deepEqual(has.filter(path => !packed.files.includes(path)), [], `${name} would be published without these files`)
  assert.deepEqual(packed.files.filter(path => hasNo.some(prefix => path.startsWith(prefix))), [], `${name} would be published with these development files`)
}

test("@supermux/core ships its build, docs, examples and license, and none of its sources", { skip }, () => {
  check("supermux-core", {
    name: "@supermux/core",
    has: ["dist/index.js", "dist/index.d.ts", "dist/supermux.js", "README.md", "REFERENCE.md", "API.md", "LICENSE", "examples/real-agents.mjs", "package.json"],
    hasNo: ["tests/", "e2e/", "src/", "scripts/"],
  })
})

test("supermux-core, the alias, ships its re-exports and license", { skip }, () => {
  check("supermux-core-alias", { name: "supermux-core", has: ["index.js", "index.d.ts", "LICENSE", "README.md"] })
})

test("supermux, the held name, ships its command and license", { skip }, () => {
  check("supermux-placeholder", { name: "supermux", has: ["README.md", "LICENSE", "bin.js"] })
})

test("the supermux command of the held name only says where the real tool is, and fails", () => {
  const bin = join(folder("supermux-placeholder"), "bin.js")
  assert.match(readFileSync(bin, "utf8"), /^#!\/usr\/bin\/env node\n/)
  // Windows has no executable bit.
  if (process.platform !== "win32") assert.ok(statSync(bin).mode & 0o111, "bin.js is not executable")
  const run = spawnSync(process.execPath, [bin], { env, encoding: "utf8" })
  // The real supermux CLI exists, installed from supermux.dev. This is not it, so a script that runs it must fail.
  assert.equal(run.status, 1)
  assert.equal(run.stdout, "")
  assert.equal(run.stderr, [
    'This npm package only reserves the name "supermux".',
    "The supermux CLI installs with: curl -fsSL https://supermux.dev/install.sh | sh",
    "The library is on npm: npm i @supermux/core",
    "",
  ].join("\n"))
})
