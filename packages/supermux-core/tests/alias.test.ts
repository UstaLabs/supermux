import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { LIBRARY_VERSION } from "../src/version.js"

const root = join(import.meta.dir, "..", "..")
const read = (path: string) => JSON.parse(readFileSync(join(root, path), "utf8"))

test("the core package is @supermux/core and public", () => {
  const pkg = read("supermux-core/package.json")
  expect(pkg.name).toBe("@supermux/core")
  expect(pkg.private).toBeUndefined()
  expect(pkg.publishConfig).toEqual({ access: "public" })
  expect(pkg.repository).toEqual({ type: "git", url: "git+https://github.com/UstaLabs/supermux.git", directory: "packages/supermux-core" })
})

test("LIBRARY_VERSION, the version the drivers announce to their agents, is the core package's version", () => {
  expect(LIBRARY_VERSION).toBe(read("supermux-core/package.json").version)
})

test("the core package rebuilds dist when it is packed, so a publish from a clean checkout cannot ship without it", () => {
  // node, not rm: the same line runs on Windows too.
  expect(read("supermux-core/package.json").scripts.prepack).toBe(`node -e "require('fs').rmSync('dist',{recursive:true,force:true})" && tsc -p tsconfig.json`)
})

test("every published package carries the repository's license", () => {
  const license = readFileSync(join(root, "..", "LICENSE"), "utf8")
  for (const folder of ["supermux-core", "supermux-core-alias", "supermux-placeholder"]) {
    expect(readFileSync(join(root, folder, "LICENSE"), "utf8")).toBe(license)
  }
})

test("supermux-core re-exports every library subpath of @supermux/core at the same version", () => {
  const core = read("supermux-core/package.json")
  const alias = read("supermux-core-alias/package.json")
  expect(alias.name).toBe("supermux-core")
  expect(alias.version).toBe(core.version)
  expect(alias.dependencies).toEqual({ "@supermux/core": core.version })
  const librarySubpaths = Object.keys(core.exports).filter(key => key !== "./mcp-bridge").sort()
  expect(Object.keys(alias.exports).sort()).toEqual(librarySubpaths)
  for (const [subpath, target] of Object.entries(alias.exports as Record<string, { types: string; import: string }>)) {
    const specifier = subpath === "." ? "@supermux/core" : `@supermux/core/${subpath.slice(2)}`
    for (const file of [target.import, target.types]) {
      expect(readFileSync(join(root, "supermux-core-alias", file), "utf8").trim()).toBe(`export * from "${specifier}"`)
    }
  }
})

test("the supermux name is held with a pointer to the library", () => {
  const placeholder = read("supermux-placeholder/package.json")
  expect(placeholder.name).toBe("supermux")
  expect(readFileSync(join(root, "supermux-placeholder", "README.md"), "utf8")).toContain("@supermux/core")
})

test("the held supermux name says where the real CLI installs from, the way the repository README does", () => {
  const install = "curl -fsSL https://supermux.dev/install.sh | sh"
  expect(readFileSync(join(root, "..", "README.md"), "utf8")).toContain(install)
  for (const file of ["README.md", "bin.js"]) {
    expect(readFileSync(join(root, "supermux-placeholder", file), "utf8")).toContain(install)
  }
})

test("the held supermux name ships a supermux command, so it is not a name with nothing in it", () => {
  const placeholder = read("supermux-placeholder/package.json")
  expect(placeholder.bin).toEqual({ supermux: "bin.js" })
  expect(placeholder.files).toContain("bin.js")
})
