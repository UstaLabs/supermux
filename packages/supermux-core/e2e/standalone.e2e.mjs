import { test } from "node:test"
import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

// @supermux/platform is a private workspace package, never published (Ahmet's decision on #29): the published core
// carries it in dist/vendor/platform. This proves the packed core works where @supermux/platform does not exist: its
// package.json does not ask for it, no file imports it by name, and a fresh project that installs only the tarball
// can import core (whose keeper and store modules use the package) and run code that goes through it.

const core = fileURLToPath(new URL("../", import.meta.url))
const env = { ...process.env, PATH: `${dirname(process.execPath)}${delimiter}${process.env.PATH ?? ""}`, npm_config_update_notifier: "false", npm_config_fund: "false", npm_config_audit: "false" }
const npm = [join(dirname(process.execPath), "npm"), "npm"].find(candidate => spawnSync(candidate, ["--version"], { env, stdio: "ignore" }).status === 0)
const noNpm = "no working npm beside this node or on PATH"
// Under CI a missing npm fails the test: a check that skips there stops guarding the publish without anyone noticing.
const skip = !npm && !process.env.CI && noNpm
const NAMED = /(["'])@supermux\/platform\1/

/** The tarball `npm pack` writes for the core as built (dist present; --ignore-scripts keeps prepack's rm away). */
function packCore(into) {
  const run = spawnSync(npm, ["pack", "--json", "--ignore-scripts", "--pack-destination", into], { cwd: core, env, encoding: "utf8" })
  assert.equal(run.status, 0, `npm pack failed: ${run.error ?? run.stderr}`)
  return join(into, JSON.parse(run.stdout)[0].filename)
}

function filesUnder(dir) {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? filesUnder(path) : [path]
  })
}

test("the packed core neither depends on nor imports @supermux/platform", { skip }, () => {
  const work = mkdtempSync(join(tmpdir(), "core-pack-"))
  try {
    const tarball = packCore(work)
    const out = join(work, "x")
    mkdirSync(out)
    assert.equal(spawnSync("tar", ["-xzf", tarball, "-C", out]).status, 0, "tar -xzf failed")
    const pkg = JSON.parse(readFileSync(join(out, "package", "package.json"), "utf8"))
    for (const field of ["dependencies", "peerDependencies", "optionalDependencies", "bundleDependencies", "bundledDependencies"]) {
      const value = pkg[field]
      assert.ok(!(Array.isArray(value) ? value : Object.keys(value ?? {})).includes("@supermux/platform"), `${field} names @supermux/platform`)
    }
    const naming = filesUnder(join(out, "package", "dist")).filter(f => /\.(m?js|d\.ts)$/.test(f) && NAMED.test(readFileSync(f, "utf8")))
    assert.deepEqual(naming, [], "dist still imports @supermux/platform by name")
    assert.ok(existsSync(join(out, "package", "dist", "vendor", "platform", "index.js")), "dist/vendor/platform is missing")
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
})

test("a project that installs only the packed core runs it, without @supermux/platform", { skip, timeout: 300_000 }, () => {
  const work = mkdtempSync(join(tmpdir(), "core-standalone-"))
  try {
    const tarball = packCore(work)
    const app = join(work, "app")
    mkdirSync(app)
    writeFileSync(join(app, "package.json"), JSON.stringify({ name: "standalone-consumer", private: true, type: "module" }))
    const install = spawnSync(npm, ["install", "--no-save", "--omit=dev", "--ignore-scripts", tarball], { cwd: app, env, encoding: "utf8" })
    assert.equal(install.status, 0, `npm install failed: ${install.error ?? install.stderr}`)
    assert.ok(!existsSync(join(app, "node_modules", "@supermux", "platform")), "@supermux/platform got installed")
    // Importing core loads the keeper and store modules, which import the vendored package; the stale-lock reclaim
    // and the keeper's liveness check go through proc.isAlive at run time.
    const probe = `
      const core = await import("@supermux/core")
      if (typeof core.createCore !== "function") throw new Error("no createCore")
      const { pathToFileURL } = await import("node:url")
      const { join } = await import("node:path")
      const vendored = join(process.cwd(), "node_modules", "@supermux", "core", "dist", "vendor", "platform", "index.js")
      const { proc } = await import(pathToFileURL(vendored).href)
      if (proc.isAlive(process.pid) !== true) throw new Error("proc.isAlive is not the platform's")
      console.log("standalone-ok")
    `
    const run = spawnSync(process.execPath, ["--input-type=module", "-e", probe], { cwd: app, env, encoding: "utf8" })
    assert.equal(run.status, 0, `the packed core did not run: ${run.stderr}`)
    assert.match(run.stdout, /standalone-ok/)
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
})
