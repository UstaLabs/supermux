// src/channels/web/forge-routes.test.ts
import { test, expect, afterAll } from "bun:test"
import { rmSync } from "fs"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { ForgeStore } from "../../core/forge/store"
import { ForgeService } from "../../core/forge/service"

const projectsRoot = "/tmp/mux-forge-routes-" + process.pid
afterAll(() => rmSync(projectsRoot, { recursive: true, force: true }))

test("forge service wiring: connections start empty, createLocal then lists", async () => {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const svc = new ForgeService(new ForgeStore(db),
    { projectsRoot, sshRoot: "/tmp/mux-ssh-" + process.pid, credentialHelperPath: "/tmp/mux/bin/mux-credential" })
  expect(svc.connections()).toEqual([])
  await svc.createLocal("demo")
  expect(svc.listCloned().some((c) => c.name === "demo")).toBe(true)
})
