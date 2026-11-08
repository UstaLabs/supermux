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
import type { Println } from "./types"
import { STOP_TOKEN_FILE } from "../session-manager/stop-token"
import { envText, readEnvText } from "../../shared/env-file"

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
  const map = existsSync(envPath) ? readEnvText(readFileSync(envPath, "utf8")) : new Map<string, string>()
  map.set("MUX_WEB_PORT", port)
  map.set("MUX_WEB_PUBLIC_URL", url)
  // Wildcard (subdomain) mode is env-driven: set it when present, clear a stale
  // value otherwise (so `connect --off` / a re-run without wildcard can't leave a
  // base domain that silently breaks routing).
  if (proxyBaseDomain) map.set("MUX_PROXY_BASE_DOMAIN", proxyBaseDomain)
  else map.delete("MUX_PROXY_BASE_DOMAIN")
  writeFileSync(envPath, envText(map))
  chmodSync(envPath, 0o600)
}

/** Enable/disable the hosted relay without deleting the key. Keeping an empty
 * value records an explicit opt-out, so a later idempotent `supermux setup`
 * does not silently turn it back on. */
export function setEnvRelayDomain(stateDir: string, domain: string): void {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  const envPath = join(stateDir, ".env")
  const map = existsSync(envPath) ? readEnvText(readFileSync(envPath, "utf8")) : new Map<string, string>()
  map.set("MUX_RELAY_DOMAIN", domain)
  writeFileSync(envPath, envText(map))
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

/** The running broker's local port: [flagPort], else MUX_WEB_PORT in [stateDir]'s .env, else 8787. */
export function localBrokerPort(stateDir: string, flagPort?: string): string {
  if (flagPort) return flagPort
  try {
    const port = readEnvText(readFileSync(join(stateDir, ".env"), "utf8")).get("MUX_WEB_PORT")?.trim()
    if (port) return port
  } catch {
    /* no .env yet */
  }
  return "8787"
}

/**
 * What the running broker answered POST /internal/restart: "restarting" (202), "unsupervised" (409: nothing would
 * bring it back), "unreachable" (no token in the state directory, or no answer), or any other HTTP status.
 */
export type BrokerRestartAnswer = "restarting" | "unsupervised" | "unreachable" | number

/**
 * Asks the running broker to restart itself under whatever runs it: systemd, launchd, or the desktop app's unit, agent
 * or Windows task (update/apply.ts restartService knows which; this process does not). Uses its local
 * /internal/restart with the per-boot token in [stateDir]. A restart the broker scheduled leaves its agents running.
 */
export async function askBrokerToRestart(stateDir: string, port: string, fetchImpl: typeof fetch = fetch): Promise<BrokerRestartAnswer> {
  let token: string
  try { token = readFileSync(join(stateDir, STOP_TOKEN_FILE), "utf8").trim() } catch { return "unreachable" }
  try {
    const r = await fetchImpl(`http://127.0.0.1:${port}/internal/restart`, { method: "POST", headers: { "x-supermux-stop-token": token } })
    return r.status === 202 ? "restarting" : r.status === 409 ? "unsupervised" : r.status
  } catch {
    return "unreachable"
  }
}

/** [askBrokerToRestart], for `supermux connect --restart`: says what happened, and what to do when it could not. */
export async function restartBroker(stateDir: string, port: string, println: Println, fetchImpl: typeof fetch = fetch): Promise<boolean> {
  const answer = await askBrokerToRestart(stateDir, port, fetchImpl)
  if (answer === "restarting") {
    println("Restarted the broker ✔")
    return true
  }
  println(answer === "unreachable"
    ? "The broker is not running; it picks the new settings up when it starts."
    : answer === "unsupervised"
      ? "The broker does not run under a service manager, so it cannot restart itself: restart it yourself (quit and reopen the supermux app, or run it again)."
      : `Could not restart the broker (HTTP ${answer}): restart it yourself.`)
  return false
}
