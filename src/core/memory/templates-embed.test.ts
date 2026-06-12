import { describe, expect, test } from "bun:test"
import { TEMPLATES } from "./templates-embedded"
import { readFileSync } from "fs"
import { join } from "path"

describe("embedded memory templates", () => {
  test("every template matches its on-disk source byte-for-byte", () => {
    const dir = join(import.meta.dirname, "templates")
    for (const [name, content] of Object.entries(TEMPLATES)) {
      expect(content).toBe(readFileSync(join(dir, name), "utf8"))
    }
  })

  test("the full fixed set is present", () => {
    expect(Object.keys(TEMPLATES).sort()).toEqual([
      "agents.md.tmpl", "conventions.md.tmpl", "domain.md.tmpl",
      "identity.md.tmpl", "preferences.md.tmpl", "soul.md.tmpl",
    ])
  })
})
