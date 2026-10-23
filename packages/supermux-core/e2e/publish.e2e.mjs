import { test } from "node:test"
import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

// scripts/publish-npm.sh is what the publish-core job of .github/workflows/release.yml runs for each package. Here a stub npm stands in
// for the registry: it logs every call and answers `npm view` from the list of what the test says is published.
const script = fileURLToPath(new URL("../../../scripts/publish-npm.sh", import.meta.url))
// The script and the stub npm are sh.
const skip = process.platform === "win32" && "the publish script and the stub npm are sh scripts"

const stubNpm = `#!/bin/sh
echo "$(pwd -P) $*" >> "$NPM_LOG"
case "$1" in
  view)
    case " $PUBLISHED " in
      *" $2 "*) echo "$2" | sed 's/.*@//'; exit 0 ;;
    esac
    echo "npm error code E404" >&2
    exit 1 ;;
  publish) exit "$PUBLISH_EXIT" ;;
esac
exit 99
`

/** One run of the script on a package `name` at `version`, with `published` (name@version specs) already on the registry. */
function run({ name = "@supermux/core", version = "0.1.0", published = [], publishExit = 0 }) {
  const work = mkdtempSync(join(tmpdir(), "supermux-publish-"))
  try {
    const folder = join(work, "package")
    const bin = join(work, "bin")
    const log = join(work, "npm.log")
    mkdirSync(folder)
    mkdirSync(bin)
    writeFileSync(join(folder, "package.json"), JSON.stringify({ name, version, type: "module" }))
    writeFileSync(join(bin, "npm"), stubNpm, { mode: 0o755 })
    writeFileSync(log, "")
    // The stub first, then this node: the script reads package.json with `node -p`.
    const PATH = [bin, dirname(process.execPath), process.env.PATH ?? ""].join(delimiter)
    const env = { ...process.env, PATH, NPM_LOG: log, PUBLISHED: published.join(" "), PUBLISH_EXIT: String(publishExit) }
    const done = spawnSync("bash", [script, folder], { env, encoding: "utf8" })
    const calls = readFileSync(log, "utf8").split("\n").filter(Boolean).map(line => {
      const space = line.indexOf(" ")
      return { cwd: line.slice(0, space), args: line.slice(space + 1) }
    })
    return { status: done.status, stdout: done.stdout, stderr: done.stderr, calls, folder: realpathSync(folder) }
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

test("publishes a version the registry does not have yet, from the package folder, with provenance", { skip }, () => {
  const done = run({ published: ["@supermux/core@0.0.9"] })
  assert.equal(done.status, 0, done.stderr)
  assert.deepEqual(done.calls.map(call => call.args), ["view @supermux/core@0.1.0 version", "publish --provenance --access public"])
  assert.equal(done.calls[1].cwd, done.folder)
})

test("publishes an unscoped package by its own name", { skip }, () => {
  const done = run({ name: "supermux-core" })
  assert.equal(done.status, 0, done.stderr)
  assert.deepEqual(done.calls.map(call => call.args), ["view supermux-core@0.1.0 version", "publish --provenance --access public"])
})

test("skips a version the registry already has, so a rerun of a half-failed release publishes only what is missing", { skip }, () => {
  const done = run({ published: ["@supermux/core@0.1.0"] })
  assert.equal(done.status, 0, done.stderr)
  assert.deepEqual(done.calls.map(call => call.args), ["view @supermux/core@0.1.0 version"])
  assert.match(done.stdout, /@supermux\/core@0\.1\.0 is already on the registry/)
})

test("publishes a prerelease under the next tag, never as latest", { skip }, () => {
  const done = run({ version: "0.2.0-rc.1" })
  assert.equal(done.status, 0, done.stderr)
  assert.deepEqual(done.calls.map(call => call.args), ["view @supermux/core@0.2.0-rc.1 version", "publish --provenance --access public --tag next"])
})

test("skips a prerelease the registry already has", { skip }, () => {
  const done = run({ version: "0.2.0-rc.1", published: ["@supermux/core@0.2.0-rc.1"] })
  assert.equal(done.status, 0, done.stderr)
  assert.deepEqual(done.calls.map(call => call.args), ["view @supermux/core@0.2.0-rc.1 version"])
})

test("fails when npm publish fails", { skip }, () => {
  const done = run({ publishExit: 1 })
  assert.notEqual(done.status, 0)
  assert.equal(done.calls.at(-1).args, "publish --provenance --access public")
})

test("needs the package folder", { skip }, () => {
  const done = spawnSync("bash", [script], { encoding: "utf8" })
  assert.notEqual(done.status, 0)
  assert.match(done.stderr, /usage: publish-npm\.sh <package folder>/)
})
