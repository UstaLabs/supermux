#!/usr/bin/env node
// @supermux/platform is a private workspace package, never published (Ahmet's decision on #29: bundle it). Core's own
// sources import it by name, which the workspace resolves for the broker, the tests and the Docker image; the published
// core carries it instead. After `tsc`, this copies the package's build into dist/vendor/platform and points every
// import of it in core's dist (JS and declarations) there, then fails if anything still names the package.
//
// Usage (core's build and prepack): node scripts/vendor-platform.mjs   — after `tsc -p ../platform/tsconfig.json`.
import { cpSync, existsSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import { dirname, join, relative, sep } from "node:path"
import { fileURLToPath } from "node:url"

const core = fileURLToPath(new URL("..", import.meta.url))
const dist = join(core, "dist")
const built = join(core, "..", "platform", "dist")
const vendor = join(dist, "vendor", "platform")
// A quoted specifier only: comments that mention the package are not imports.
const NAMED = /(["'])@supermux\/platform\1/g

if (!existsSync(join(built, "index.js"))) {
  console.error(`vendor-platform: build @supermux/platform first (no ${join(built, "index.js")})`)
  process.exit(1)
}
if (!existsSync(dist)) {
  console.error(`vendor-platform: build core first (no ${dist})`)
  process.exit(1)
}

rmSync(vendor, { recursive: true, force: true })
cpSync(built, vendor, { recursive: true })

/** Core's emitted files outside the vendored package: the ones that may import it. */
function emitted(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (path === vendor) return []
    if (statSync(path).isDirectory()) return emitted(path)
    return /\.(m?js|d\.ts)$/.test(name) ? [path] : []
  })
}

let pointed = 0
for (const file of emitted(dist)) {
  const text = readFileSync(file, "utf8")
  if (!NAMED.test(text)) continue
  NAMED.lastIndex = 0
  let target = relative(dirname(file), join(vendor, "index.js")).split(sep).join("/")
  if (!target.startsWith(".")) target = `./${target}`
  writeFileSync(file, text.replace(NAMED, (_, quote) => `${quote}${target}${quote}`))
  pointed++
}

const left = emitted(dist).filter((file) => {
  NAMED.lastIndex = 0
  return NAMED.test(readFileSync(file, "utf8"))
})
if (left.length) {
  console.error(`vendor-platform: still imports @supermux/platform by name: ${left.map((f) => relative(dist, f)).join(", ")}`)
  process.exit(1)
}
console.log(`vendor-platform: dist/vendor/platform in, ${pointed} file(s) pointed at it`)
