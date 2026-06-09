#!/usr/bin/env bun
// Print the last N hours (default 24) of broker messages, grouped by session.
// The nightly knowledge curator runs this to get its raw material.
import { Database } from "bun:sqlite"
import { join } from "path"
import { STATE_DIR } from "../src/shared/paths"
import { queryLast24h, formatLast24h } from "../src/core/curator/dump"

const hours = Number(process.env.CURATOR_WINDOW_HOURS ?? 24)
const since = new Date(Date.now() - hours * 3600 * 1000).toISOString()

const db = new Database(join(STATE_DIR, "db.sqlite3"), { readonly: true })
const rows = queryLast24h(db, since)
db.close()

console.log(`# Last ${hours}h of sessions (since ${since}) — ${rows.length} messages`)
console.log(formatLast24h(rows))
