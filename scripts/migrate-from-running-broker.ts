#!/usr/bin/env bun
/**
 * One-shot migration: snapshot the running pre-sqlite broker's in-memory
 * message log via its HTTP API, stop the broker, write rows into the new
 * sqlite store, then start the broker back up.
 *
 * Safe by construction:
 *   - Old data lives only in memory; nothing on disk to corrupt.
 *   - DB writes happen with the broker stopped (no live-process race).
 *   - INSERTs use INSERT OR IGNORE keyed on `messages.id` — re-runnable.
 *   - Snapshot JSON is kept under /tmp until both broker is up + verified.
 *
 * Usage: bun scripts/migrate-from-running-broker.ts
 */

import { DeviceStore } from "../src/channels/web/device-store"
import { openDb, runMigrations } from "../src/core/storage/db"
import { join } from "path"
import { spawnSync } from "child_process"
import { writeFileSync, existsSync } from "fs"

const STATE_DIR = process.env.MUX_STATE_DIR ?? join(process.env.HOME ?? "", ".mux/state")
const MUX_WEB_PORT = parseInt(process.env.MUX_WEB_PORT ?? "9898", 10)
const SNAPSHOT_PATH = `/tmp/cmux-migration-snapshot-${Date.now()}.json`
const TEMP_DEVICE = "migration-temp"
const MIGRATIONS_DIR = join(import.meta.dir, "../src/core/storage/migrations")

function sh(cmd: string, args: string[]): { ok: boolean; out: string } {
  const r = spawnSync(cmd, args, { encoding: "utf8" })
  return { ok: r.status === 0, out: (r.stdout ?? "") + (r.stderr ?? "") }
}

async function waitFor(predicate: () => Promise<boolean>, timeoutMs: number, intervalMs = 500): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  return false
}

async function main() {
  console.log(`>>> migration starting (state dir: ${STATE_DIR}, port: ${MUX_WEB_PORT})`)

  const devicesFile = join(STATE_DIR, "devices.json")
  if (!existsSync(devicesFile)) {
    throw new Error(`devices.json not found at ${devicesFile}`)
  }

  // --- Step 1: mint temp device token ---
  console.log(`[1/8] Minting temp device "${TEMP_DEVICE}"…`)
  const ds = new DeviceStore(devicesFile)
  const minted = ds.mint(TEMP_DEVICE)
  const token = minted.token
  console.log(`      device "${minted.name}" minted (token length ${token.length})`)

  let snapshot: Record<string, any[]> = {}
  let revokeOnExit = true

  try {
    // --- Step 2: probe broker is reachable ---
    console.log("[2/8] Probing broker /sessions…")
    const probe = await fetch(`http://127.0.0.1:${MUX_WEB_PORT}/sessions`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!probe.ok) throw new Error(`broker /sessions returned ${probe.status} — is it running?`)
    const sessions = (await probe.json()) as Array<{ name: string }>
    console.log(`      found ${sessions.length} sessions: ${sessions.map((s) => s.name).join(", ")}`)

    // --- Step 3: snapshot per-session message log ---
    console.log("[3/8] Snapshotting per-session message logs…")
    for (const s of sessions) {
      const res = await fetch(`http://127.0.0.1:${MUX_WEB_PORT}/sessions/${encodeURIComponent(s.name)}/messages`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) {
        console.warn(`      WARN: ${s.name} returned ${res.status}; skipping`)
        snapshot[s.name] = []
        continue
      }
      const msgs = (await res.json()) as any[]
      snapshot[s.name] = msgs
      console.log(`      ${s.name}: ${msgs.length} message(s)`)
    }
    writeFileSync(SNAPSHOT_PATH, JSON.stringify(snapshot, null, 2))
    console.log(`      snapshot written to ${SNAPSHOT_PATH}`)

    // --- Step 4: stop broker ---
    console.log("[4/8] Stopping broker via systemctl…")
    const stop = sh("systemctl", ["--user", "stop", "supermux"])
    if (!stop.ok) throw new Error(`systemctl stop failed: ${stop.out}`)

    const stoppedOk = await waitFor(async () => {
      try {
        await fetch(`http://127.0.0.1:${MUX_WEB_PORT}/sessions`, { headers: { Authorization: `Bearer ${token}` } })
        return false
      } catch {
        return true
      }
    }, 10_000)
    if (!stoppedOk) throw new Error("broker still answering on port after stop")
    console.log("      broker stopped")

    // --- Step 5: open / migrate sqlite ---
    console.log("[5/8] Opening sqlite + running migrations…")
    const dbPath = join(STATE_DIR, "db.sqlite3")
    const db = openDb(dbPath)
    runMigrations(db, MIGRATIONS_DIR)
    console.log(`      db ready at ${dbPath}`)

    // --- Step 6: insert rows ---
    console.log("[6/8] Inserting snapshot rows…")
    const insert = db.prepare(`
      INSERT OR IGNORE INTO messages
        (id, session, ts, direction, channel, chat_id, message_id, op, text, edited_at, attachments, reactions)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `)
    let inserted = 0
    let skipped = 0
    const tx = db.transaction(() => {
      for (const [session, entries] of Object.entries(snapshot)) {
        for (const e of entries) {
          const info = insert.run(
            e.id,
            session,
            e.ts,
            e.direction,
            e.channel,
            e.chat_id,
            e.message_id ?? null,
            e.op ?? null,
            e.text ?? null,
            e.edited_at ?? null,
            e.attachments ? JSON.stringify(e.attachments) : null,
            e.reactions ? JSON.stringify(e.reactions) : null,
          )
          if (info.changes === 0) skipped++
          else inserted++
        }
      }
    })
    tx()
    db.close()
    console.log(`      inserted ${inserted} rows; skipped ${skipped} duplicate(s)`)

    // --- Step 7: start broker ---
    console.log("[7/8] Starting broker via systemctl…")
    const start = sh("systemctl", ["--user", "start", "supermux"])
    if (!start.ok) throw new Error(`systemctl start failed: ${start.out}`)

    // wait for broker to answer (it'll have a fresh /sessions endpoint)
    const upOk = await waitFor(async () => {
      try {
        const r = await fetch(`http://127.0.0.1:${MUX_WEB_PORT}/sessions`, { headers: { Authorization: `Bearer ${token}` } })
        return r.ok
      } catch {
        return false
      }
    }, 20_000)
    if (!upOk) throw new Error("broker did not come back up within 20s")
    console.log("      broker is back up")

    console.log(`[8/8] Migration complete — ${inserted} rows in sqlite, snapshot kept at ${SNAPSHOT_PATH} as a safety net.`)
  } finally {
    if (revokeOnExit) {
      console.log("Cleaning up: revoking temp device…")
      const ds2 = new DeviceStore(devicesFile)
      const ok = ds2.revoke(TEMP_DEVICE)
      console.log(`      revoke ${ok ? "ok" : "skipped (already gone)"}`)
    }
  }
}

main().catch((e) => {
  console.error("MIGRATION FAILED:", e instanceof Error ? e.message : String(e))
  console.error("If the broker is stopped, restart it manually: systemctl --user start supermux")
  process.exit(1)
})
