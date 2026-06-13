// src/channels/web/forge-routes.test.ts
import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { ForgeStore } from "../../core/forge/store"
import { ForgeService } from "../../core/forge/service"

test("forge service wiring: connections start empty, createLocal then lists", async () => {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const svc = new ForgeService(new ForgeStore(db),
    { projectsRoot: "/tmp/mux-forge-routes-" + process.pid, sshRoot: "/tmp/mux-ssh-" + process.pid, credentialHelperPath: "/tmp/mux/bin/mux-credential" })
  expect(svc.connections()).toEqual([])
  await svc.createLocal("demo")
  expect(svc.listCloned().some((c) => c.name === "demo")).toBe(true)
})
