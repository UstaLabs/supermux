import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { buildDomainIndex } from "../src/core/memory/index-builder"

test("digest files are excluded from the domain index", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-idx-"))
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "infra.md"), "---\ndescription: infra notes\n---\n")
  writeFileSync(join(dir, "infra.digest.md"), "## Current\nstuff\n")
  const idx = buildDomainIndex(dir)
  expect(idx).toContain("- infra: infra notes")
  expect(idx).not.toContain("infra.digest")
  expect(idx).not.toContain("- infra.digest:")
})
