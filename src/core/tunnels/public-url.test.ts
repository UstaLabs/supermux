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

// The CLI guessed the service: `systemctl --user restart supermux` / launchd `dev.supermux.broker`, the names `supermux
// setup` installs, never the desktop app's (supermux-host.service, dev.supermux.host, the "Supermux Host" task), and
// systemctl on Windows. It asks the running broker instead, which knows what runs it (update/apply.ts restartService).
function brokerAt(status: number, seen: Array<{ url: string; token: string | null }> = []) {
  return (async (url: string, init?: RequestInit) => {
    seen.push({ url, token: new Headers(init?.headers).get("x-supermux-stop-token") })
    return new Response(null, { status })
  }) as unknown as typeof fetch
}

test("restartBroker asks the running broker to restart itself, with the state directory's token", async () => {
  const dir = tmp()
  writeFileSync(join(dir, "stop-token"), "tok-123")
  const seen: Array<{ url: string; token: string | null }> = []
  const ok = await restartBroker(dir, "8787", () => {}, brokerAt(202, seen))
  expect(ok).toBe(true)
  expect(seen).toEqual([{ url: "http://127.0.0.1:8787/internal/restart", token: "tok-123" }])
})

test("restartBroker says what to do when nothing would bring the broker back, or it is not running", async () => {
  const dir = tmp()
  writeFileSync(join(dir, "stop-token"), "tok-123")
  const out: string[] = []
  expect(await restartBroker(dir, "8787", (s) => out.push(s), brokerAt(409))).toBe(false)
  expect(out.join("\n")).toContain("restart it yourself")
  const down = (async () => { throw new TypeError("Unable to connect") }) as unknown as typeof fetch
  expect(await restartBroker(dir, "8787", (s) => out.push(s), down)).toBe(false)
  expect(await restartBroker(tmp(), "8787", (s) => out.push(s), brokerAt(202))).toBe(false)
  expect(out.join("\n")).toContain("not running")
})

test("writeEnvPublicUrl sets MUX_PROXY_BASE_DOMAIN when a base domain is given", () => {
  const dir = tmp()
  writeEnvPublicUrl(dir, "8787", "https://mux.example.com", "example.com")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).toContain("MUX_PROXY_BASE_DOMAIN=example.com")
})

test("writeEnvPublicUrl clears a stale MUX_PROXY_BASE_DOMAIN when none is given, keeps other keys", () => {
  const dir = tmp()
  writeFileSync(join(dir, ".env"), "MUX_PROXY_BASE_DOMAIN=old.example.com\nFOO=bar\n")
  writeEnvPublicUrl(dir, "8787", "http://localhost:8787")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).not.toContain("MUX_PROXY_BASE_DOMAIN")
  expect(env).toContain("FOO=bar")
})

// A .env a Windows editor saved (CRLF): the old reader skipped every such line, so this rewrite dropped them all.
test("writeEnvPublicUrl keeps the keys of a .env saved with CRLF line ends", () => {
  const dir = tmp()
  writeFileSync(join(dir, ".env"), "MUX_TELEGRAM_BOT_TOKEN=123:abc\r\nMUX_RELAY_DOMAIN=relay.example\r\n")
  writeEnvPublicUrl(dir, "8787", "https://x.trycloudflare.com")
  const text = readFileSync(join(dir, ".env"), "utf8")
  expect(text).toContain("MUX_TELEGRAM_BOT_TOKEN=123:abc\n")
  expect(text).toContain("MUX_RELAY_DOMAIN=relay.example\n")
  expect(text).toContain("MUX_WEB_PUBLIC_URL=https://x.trycloudflare.com\n")
})
