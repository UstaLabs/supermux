import { test, expect } from "bun:test"
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb } from "../storage/db"
import { SettingsStore } from "../settings/store"
import { writeEnvPublicUrl, setStorePublicUrl, mintPairLink, restartBroker } from "./public-url"
import type { Run } from "./types"

function tmp(): string {
  return mkdtempSync(join(tmpdir(), "mux-tun-"))
}

test("writeEnvPublicUrl clobbers an existing MUX_WEB_PUBLIC_URL, keeps other keys", () => {
  const dir = tmp()
  writeFileSync(
    join(dir, ".env"),
    "MUX_WEB_PORT=8787\nMUX_WEB_PUBLIC_URL=http://localhost:8787\nFOO=bar\n",
  )
  writeEnvPublicUrl(dir, "8787", "https://x.trycloudflare.com")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).toContain("MUX_WEB_PUBLIC_URL=https://x.trycloudflare.com")
  expect(env).not.toContain("localhost:8787")
  expect(env).toContain("FOO=bar")
})

test("writeEnvPublicUrl creates .env when absent", () => {
  const dir = tmp()
  writeEnvPublicUrl(dir, "9000", "https://y.ts.net")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).toContain("MUX_WEB_PORT=9000")
  expect(env).toContain("MUX_WEB_PUBLIC_URL=https://y.ts.net")
})

test("setStorePublicUrl persists webPublicUrl + tunnel record to the store", () => {
  const dir = tmp()
  const dbPath = join(dir, "db.sqlite3")
  const seed = openDb(dbPath)
  seed.exec("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
  seed.close()

  setStorePublicUrl(dir, {
    webPublicUrl: "https://x.ts.net",
    webPort: "8787",
    tunnel: { provider: "tailscale", mode: "serve", publicUrl: "https://x.ts.net" },
  })

  const db = openDb(dbPath)
  const cfg = new SettingsStore(db).getAppConfig({})
  db.close()
  expect(cfg.webPublicUrl).toBe("https://x.ts.net")
  expect(cfg.tunnel?.provider).toBe("tailscale")
  expect(cfg.tunnel?.mode).toBe("serve")
})

test("setStorePublicUrl no-ops when the DB doesn't exist", () => {
  const dir = tmp()
  expect(() => setStorePublicUrl(dir, { webPublicUrl: "https://x" })).not.toThrow()
})

test("mintPairLink builds a /pair?t= URL and writes devices.json", () => {
  const dir = tmp()
  const link = mintPairLink(dir, "phone", "https://x.ts.net/")
  expect(link.url).toMatch(/^https:\/\/x\.ts\.net\/pair\?t=.+/)
  expect(link.url).not.toContain("//pair") // trailing slash normalized
  expect(existsSync(join(dir, "devices.json"))).toBe(true)
})

test("restartBroker runs systemctl --user restart on linux", async () => {
  if (process.platform === "darwin") return
  const calls: string[][] = []
  const run: Run = async (argv) => {
    calls.push(argv)
    return { code: 0, stdout: "", stderr: "" }
  }
  const ok = await restartBroker(run, () => {})
  expect(ok).toBe(true)
  expect(calls[0]).toEqual(["systemctl", "--user", "restart", "supermux"])
})

test("restartBroker reports failure (non-zero) without throwing", async () => {
  if (process.platform === "darwin") return
  const run: Run = async () => ({ code: 1, stdout: "", stderr: "boom" })
  const out: string[] = []
  const ok = await restartBroker(run, (s) => out.push(s))
  expect(ok).toBe(false)
  expect(out.join("\n")).toContain("journalctl")
})
