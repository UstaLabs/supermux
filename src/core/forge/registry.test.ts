// src/core/forge/registry.test.ts
import { test, expect } from "bun:test"
import { adapterFor, ADAPTERS } from "./registry"

test("registry exposes both kinds, keyed correctly", () => {
  expect(Object.keys(ADAPTERS).sort()).toEqual(["github", "gitlab"])
  expect(adapterFor("github").kind).toBe("github")
  expect(adapterFor("gitlab").kind).toBe("gitlab")
})

test("adapterFor throws on an unknown kind", () => {
  // @ts-expect-error testing the runtime guard
  expect(() => adapterFor("bitbucket")).toThrow()
})
