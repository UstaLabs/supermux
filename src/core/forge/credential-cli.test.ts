// src/core/forge/credential-cli.test.ts
import { test, expect } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { execFileSync } from "child_process"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { ForgeStore } from "./store"
import { credentialFill } from "./credential-cli"
import type { ForgeCredential } from "./types"

function store() { const db = openDb(":memory:"); runMigrations(db, MIGRATIONS); return new ForgeStore(db) }
const cred: ForgeCredential = { id: "github:github.com:a", kind: "github", host: "github.com",
  apiBase: "https://api.github.com", label: "", account: { login: "a" }, source: "pat",
  transport: "https", status: "ok", token: "ghp_secret" }

test("credentialFill emits git creds for a known connection (github → x-access-token)", () => {
  const s = store(); s.add(cred)
  expect(credentialFill(s, "github:github.com:a")).toBe("username=x-access-token\npassword=ghp_secret\n")
})

test("credentialFill is empty for an unknown connection", () => {
  expect(credentialFill(store(), "nope")).toBe("")
})

test("entrypoint: 'bun credential-cli.ts <id> get' prints creds from the STATE_DIR db; non-get is silent", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-cred-e2e-"))
  try {
    const db = openDb(join(dir, "db.sqlite3")); runMigrations(db, MIGRATIONS)
    new ForgeStore(db).add({ ...cred }) // `cred` already defined above in this file
    db.close()
    const cli = join(import.meta.dir, "credential-cli.ts")
    const run = (op: string) => execFileSync(process.execPath, [cli, "github:github.com:a", op],
      { encoding: "utf8", env: { ...process.env, MUX_STATE_DIR: dir } })
    expect(run("get")).toBe("username=x-access-token\npassword=ghp_secret\n")
    expect(run("store")).toBe("")
  } finally { rmSync(dir, { recursive: true, force: true }) }
})
