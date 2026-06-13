// src/core/forge/credential-cli.test.ts
import { test, expect } from "bun:test"
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
