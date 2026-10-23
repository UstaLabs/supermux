#!/usr/bin/env node
// Sets the version of @supermux/core everywhere it is written down: the package, its supermux-core alias (version
// and the exact dependency on the core), LIBRARY_VERSION in src/version.ts, and the examples' `^version` ranges.
// release.yml runs it with the v* tag's version (v0.14.0 → 0.14.0) before it publishes, so the core ships on every
// release at the app's version and nobody bumps four files by hand. tests/alias.test.ts holds the four together.
//
// Usage: node scripts/set-core-version.mjs <version> [repository root]
import { readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"

const [version, root = process.cwd()] = process.argv.slice(2)
if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error(`usage: set-core-version.mjs <version> [root] (got ${JSON.stringify(version)})`)
  process.exit(2)
}

const packages = join(root, "packages")
const editJson = (path, edit) => {
  const file = join(packages, path)
  const pkg = JSON.parse(readFileSync(file, "utf8"))
  edit(pkg)
  writeFileSync(file, `${JSON.stringify(pkg, null, 2)}\n`)
}

editJson("supermux-core/package.json", pkg => { pkg.version = version })
editJson("supermux-core-alias/package.json", pkg => {
  pkg.version = version
  pkg.dependencies["@supermux/core"] = version
})
for (const example of ["chat", "kanban", "recipes"]) {
  editJson(`supermux-core/examples/${example}/package.json`, pkg => { pkg.dependencies["@supermux/core"] = `^${version}` })
}

const versionFile = join(packages, "supermux-core/src/version.ts")
const source = readFileSync(versionFile, "utf8")
const stamped = source.replace(/export const LIBRARY_VERSION = "[^"]*"/, `export const LIBRARY_VERSION = "${version}"`)
if (stamped === source && !source.includes(`LIBRARY_VERSION = "${version}"`)) {
  console.error(`no LIBRARY_VERSION line in ${versionFile}`)
  process.exit(1)
}
writeFileSync(versionFile, stamped)
console.log(`@supermux/core and supermux-core are now ${version}`)
