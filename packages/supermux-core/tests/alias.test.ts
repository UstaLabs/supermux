import { expect, test } from "bun:test"
import { existsSync, readFileSync } from "node:fs"
import { join } from "node:path"

const root = join(import.meta.dir, "..", "..")
const read = (path: string) => JSON.parse(readFileSync(join(root, path), "utf8"))

test("the core package is @supermux/core and public", () => {
  const pkg = read("supermux-core/package.json")
  expect(pkg.name).toBe("@supermux/core")
  expect(pkg.private).toBeUndefined()
  expect(pkg.publishConfig).toEqual({ access: "public" })
  expect(pkg.repository).toEqual({ type: "git", url: "git+https://github.com/UstaLabs/supermux.git", directory: "packages/supermux-core" })
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
    const js = readFileSync(join(root, "supermux-core-alias", target.import), "utf8")
    const specifier = subpath === "." ? "@supermux/core" : `@supermux/core/${subpath.slice(2)}`
    expect(js.trim()).toBe(`export * from "${specifier}"`)
    expect(existsSync(join(root, "supermux-core-alias", target.types))).toBe(true)
  }
})

test("the supermux name is held with a pointer to the library", () => {
  const placeholder = read("supermux-placeholder/package.json")
  expect(placeholder.name).toBe("supermux")
  expect(readFileSync(join(root, "supermux-placeholder", "README.md"), "utf8")).toContain("@supermux/core")
})
