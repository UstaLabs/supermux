// Wiring that turns a resolved tunnel URL into a working broker public face:
//  1. write MUX_WEB_PUBLIC_URL to .env (clobber) AND the SQLite store (precedence)
//  2. restart the broker so the new origin binds (CSRF + cookie scoping)
//  3. mint a fresh pairing link (the origin changed, so old links are dead)

import { existsSync, readFileSync, writeFileSync, chmodSync, mkdirSync } from "fs"
import { join } from "path"
import qrcode from "qrcode-terminal"
import { openDb } from "../storage/db"
import { SettingsStore } from "../settings/store"
import { DeviceStore } from "../../channels/web/device-store"
import type { AppConfig } from "../settings/app-config"
import type { Run, Println } from "./types"

/** Parse `KEY=value` lines into a map (mirrors how the broker reads .env). */
function parseEnvFile(text: string): Map<string, string> {
  const map = new Map<string, string>()
  for (const line of text.split("\n")) {
    const m = line.match(/^(\w+)=(.*)$/)
    if (m) map.set(m[1]!, m[2]!)
  }
  return map
}

function serializeEnv(map: Map<string, string>): string {
  let out = ""
  for (const [k, v] of map) out += `${k}=${v}\n`
  return out
}

/**
 * Upsert MUX_WEB_PORT + MUX_WEB_PUBLIC_URL in `<stateDir>/.env`, CLOBBERING any
 * existing values (unlike `supermux setup`, which keeps existing keys — here the
 * whole point is to change the URL). Tightens perms to 600. A 4th `proxyBaseDomain`
 * sets `MUX_PROXY_BASE_DOMAIN` (subdomain mode); omitting it CLEARS any stale value.
 */
export function writeEnvPublicUrl(
  stateDir: string,
  port: string,
  url: string,
  proxyBaseDomain?: string,
): void {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  const envPath = join(stateDir, ".env")
  const map = existsSync(envPath) ? parseEnvFile(readFileSync(envPath, "utf8")) : new Map<string, string>()
  map.set("MUX_WEB_PORT", port)
  map.set("MUX_WEB_PUBLIC_URL", url)
  // Wildcard (subdomain) mode is env-driven: set it when present, clear a stale
  // value otherwise (so `connect --off` / a re-run without wildcard can't leave a
  // base domain that silently breaks routing).
  if (proxyBaseDomain) map.set("MUX_PROXY_BASE_DOMAIN", proxyBaseDomain)
  else map.delete("MUX_PROXY_BASE_DOMAIN")
  writeFileSync(envPath, serializeEnv(map))
  chmodSync(envPath, 0o600)
}

/**
 * Persist a patch to the SQLite app-config so the stored→env precedence agrees
 * with .env (a value the user set during onboarding would otherwise win over the
 * .env we just wrote). Opens the same DB the broker uses (precedent:
 * forge/credential-cli.ts). No-op when the DB or `settings` table isn't there
 * yet — the broker hasn't run, so the .env seed is authoritative anyway.
 */
export function setStorePublicUrl(stateDir: string, patch: Partial<AppConfig>): void {
  const dbPath = join(stateDir, "db.sqlite3")
  if (!existsSync(dbPath)) return
  let db
  try {
    db = openDb(dbPath)
    new SettingsStore(db).setAppConfig(patch)
  } catch {
    // DB exists but isn't a fully-migrated broker DB — leave .env as the source.
  } finally {
    try {
      db?.close()
    } catch {
      /* already closed */
    }
  }
}

/** Mint a fresh device token and build the pairing URL. No printing (testable). */
export function mintPairLink(
  stateDir: string,
  name: string,
  publicUrl: string,
): { url: string; token: string; name: string } {
  const store = new DeviceStore(join(stateDir, "devices.json"))
  const { token, name: finalName } = store.mint(name)
  const url = `${publicUrl.replace(/\/$/, "")}/pair?t=${token}`
  return { url, token, name: finalName }
}

/** Print a pairing URL + a small terminal QR. */
export function printPairLink(link: { url: string; name: string }, println: Println): void {
  println(`\nPaired "${link.name}". Open this on the device (or scan the QR):\n`)
  println(`  ${link.url}\n`)
  qrcode.generate(link.url, { small: true })
}

/**
 * Restart the broker so a changed MUX_WEB_PUBLIC_URL takes effect (the WebChannel
 * captures the origin at construction). Linux: `systemctl --user restart supermux`;
 * macOS: `launchctl kickstart`. Never throws; returns whether it succeeded.
 */
export async function restartBroker(run: Run, println: Println): Promise<boolean> {
  if (process.platform === "darwin") {
    const uid = typeof process.getuid === "function" ? process.getuid() : 0
    const target = `gui/${uid}/dev.supermux.broker`
    const r = await run(["launchctl", "kickstart", "-k", target])
    if (r.code === 0) {
      println("Restarted the broker (launchd) ✔")
      return true
    }
    println("Could not restart the broker automatically — restart it yourself:")
    println(`  launchctl kickstart -k ${target}`)
    return false
  }
  const r = await run(["systemctl", "--user", "restart", "supermux"])
  if (r.code === 0) {
    println("Restarted the broker (systemd) ✔")
    return true
  }
  println("Could not restart the broker automatically — restart it yourself:")
  println("  systemctl --user restart supermux")
  println("  (logs: journalctl --user -u supermux -n 50)")
  return false
}
