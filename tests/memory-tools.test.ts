import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { MIGRATIONS } from "../src/core/storage/migrations"
import { SearchStore } from "../src/core/search/store"

test("memory_search returns digest-ranked knowledge hits", () => {
  const home = mkdtempSync(join(tmpdir(), "mux-mt-"))
  mkdirSync(join(home, "domains"), { recursive: true })
  writeFileSync(join(home, "domains", "infra.digest.md"), "## Current\nDeploy via mux.service.\n")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const store = new SearchStore(db, home)
  store.rebuildKnowledge()
  const hits = store.searchKnowledge("deploy", { includePersonal: false, limit: 5 })
  expect(hits[0]!.scope).toBe("digest")
  expect(hits[0]!.snippet.toLowerCase()).toContain("deploy")
})
