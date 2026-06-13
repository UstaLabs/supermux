// CLI subcommand body for `supermux setup`.
// Kept here to leave src/cli.ts a thin dispatcher (mirrors src/cli-update.ts).
//
// runSetupCommand:
//   - accepts an optional `println` for testability (defaults to console.log)
//   - returns an exit code (0 = success, 1 = hard failure) — never process.exit()
//   - is idempotent: a re-run adds only MISSING .env keys and never clobbers an
//     existing key's value (a user's custom MUX_WEB_PORT survives a re-run).
//
// State-dir resolution is done by re-reading the environment HERE rather than
// importing the STATE_DIR constant from shared/paths.ts. paths.ts resolves
// MUX_HOME/MUX_STATE_DIR at import time, which a test process can't change after
// the fact; re-deriving per-call lets a test point MUX_STATE_DIR at a tmpdir and
// see the .env land there. The derivation below mirrors shared/paths.ts +
// shared/home.ts exactly.
import { existsSync, chmodSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import { IS_COMPILED } from "./shared/build-info"

// Agent CLIs we report on (PATH presence only — the user owns these logins).
const AGENT_CLIS = ["claude", "codex", "cursor-agent", "opencode"] as const

const UNIT_NAME = "supermux.service"

interface Flags {
  port: string
  publicUrl: string
  telegramToken?: string
  noService: boolean
  forceSourceUnit: boolean
}

function parseFlags(args: string[]): Flags {
  const flags: Flags = {
    port: "8787",
    publicUrl: "http://localhost:8787",
    noService: false,
    forceSourceUnit: false,
  }
  for (let i = 0; i < args.length; i++) {
    const a = args[i]
    switch (a) {
      case "--port":
        flags.port = args[++i] ?? flags.port
        break
      case "--public-url":
        flags.publicUrl = args[++i] ?? flags.publicUrl
        break
      case "--telegram-token":
        flags.telegramToken = args[++i]
        break
      case "--no-service":
        flags.noService = true
        break
      case "--force-source-unit":
        flags.forceSourceUnit = true
        break
      default:
        // Unknown flags are ignored so install.sh can pass through extras.
        break
    }
  }
  return flags
}

/** Resolve the state dir from the CURRENT env (mirrors shared/paths.ts). */
function resolveStateDir(): string {
  const home = process.env.HOME || homedir()
  const muxHome = process.env.MUX_HOME ?? join(home, ".mux")
  return process.env.MUX_STATE_DIR ?? join(muxHome, "state")
}

/** Parse `KEY=value` lines into a map (mirrors how the broker reads .env). */
function parseEnvFile(text: string): Map<string, string> {
  const map = new Map<string, string>()
  for (const line of text.split("\n")) {
    const m = line.match(/^(\w+)=(.*)$/)
    if (m) map.set(m[1]!, m[2]!)
  }
  return map
}

/** Serialize a key→value map back to `.env` text (insertion order). */
function serializeEnv(map: Map<string, string>): string {
  let out = ""
  for (const [k, v] of map) out += `${k}=${v}\n`
  return out
}

const UNIT_TEMPLATE = (execPath: string): string =>
  `[Unit]
Description=supermux broker
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${execPath}
Restart=on-failure
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=default.target
`

/**
 * Install + enable the user systemd unit. Any systemctl failure is reported via
 * println, never thrown — setup keeps going and still returns 0.
 */
function installSystemdUnit(flags: Flags, println: (s: string) => void): void {
  // 1. Source-mode guard: the unit's ExecStart points at process.execPath, which
  //    in compiled mode is the installed binary but in source mode is `bun`.
  //    Skip unless explicitly forced (tests/advanced).
  if (!IS_COMPILED && !flags.forceSourceUnit) {
    println(
      "Note: setup's systemd unit targets the compiled binary. This looks like a" +
        " source install — use SETUP.md's bun unit instead. Skipping unit creation.",
    )
    return
  }

  // 2. Detect a usable `systemctl --user`.
  const hasSystemctl = Bun.which("systemctl") !== null
  const runtimeDir = process.env.XDG_RUNTIME_DIR
  if (!hasSystemctl || !runtimeDir) {
    println(`No systemd --user available; run the broker manually: ${process.execPath}`)
    return
  }

  // 3. Write the unit.
  const home = process.env.HOME || homedir()
  const unitDir = join(home, ".config", "systemd", "user")
  const unitFile = join(unitDir, UNIT_NAME)
  try {
    mkdirSync(unitDir, { recursive: true })
    writeFileSync(unitFile, UNIT_TEMPLATE(process.execPath))
    println(`Wrote ${unitFile}`)
  } catch (e) {
    println(`Could not write the systemd unit: ${String(e)}`)
    return
  }

  // 4. daemon-reload + enable --now, each guarded.
  const sc = (sub: string[]): boolean => {
    try {
      const r = Bun.spawnSync(["systemctl", "--user", ...sub])
      return r.exitCode === 0
    } catch {
      return false
    }
  }

  sc(["daemon-reload"])
  if (!sc(["enable", "--now", "supermux"])) {
    println("Could not enable the supermux service via systemctl.")
    println("Check logs with: journalctl --user -u supermux -n 50")
    return
  }

  // 5. Give it a moment, then check is-active.
  Bun.sleepSync(2000)
  let active = false
  try {
    active = Bun.spawnSync(["systemctl", "--user", "is-active", "--quiet", "supermux"]).exitCode === 0
  } catch {
    active = false
  }
  if (active) {
    println("supermux service running ✔")
    println("(headless/SSH: run `loginctl enable-linger $USER` so it survives logout.)")
  } else {
    println("supermux service did not come up ✖")
    println("Check logs with: journalctl --user -u supermux -n 50")
  }
}

/**
 * `supermux setup`
 *
 * @param args    process.argv.slice(3) from the dispatcher
 * @param println optional output sink (default console.log); injected by tests
 */
export async function runSetupCommand(
  args: string[],
  println: (s: string) => void = console.log,
): Promise<number> {
  const flags = parseFlags(args)

  // ── State dir + .env ─────────────────────────────────────────────────────
  const stateDir = resolveStateDir()
  const envPath = join(stateDir, ".env")
  try {
    mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  } catch (e) {
    println(`error: could not create state dir ${stateDir}: ${String(e)}`)
    return 1
  }

  const existed = existsSync(envPath)
  const map = existed ? parseEnvFile(readFileSync(envPath, "utf8")) : new Map<string, string>()

  // Desired keys. Token only if one was supplied.
  const desired: Array<[string, string]> = [
    ["MUX_WEB_PORT", flags.port],
    ["MUX_WEB_PUBLIC_URL", flags.publicUrl],
  ]
  if (flags.telegramToken) desired.push(["MUX_TELEGRAM_BOT_TOKEN", flags.telegramToken])

  let changed = false
  for (const [k, v] of desired) {
    if (map.has(k)) {
      println(`kept existing ${k}`)
    } else {
      map.set(k, v)
      changed = true
      println(`added ${k}`)
    }
  }

  // Write only if we added something or the file didn't exist yet.
  if (changed || !existed) {
    writeFileSync(envPath, serializeEnv(map))
  }
  // Always tighten perms (cheap, and fixes a stray loose mode on re-run).
  chmodSync(envPath, 0o600)

  // ── systemd unit ─────────────────────────────────────────────────────────
  if (flags.noService) {
    println("Skipping systemd unit (--no-service).")
  } else {
    installSystemdUnit(flags, println)
  }

  // ── Agent-CLI report ─────────────────────────────────────────────────────
  println("Agent CLIs on PATH:")
  let anyAgent = false
  for (const cli of AGENT_CLIS) {
    const found = Bun.which(cli) !== null
    if (found) anyAgent = true
    println(`  ${found ? "✔" : "–"} ${cli}`)
  }
  if (!anyAgent) {
    println("Install + log into at least one agent CLI before spawning sessions.")
  }

  // ── Linger + final ───────────────────────────────────────────────────────
  println("Headless/SSH? Run `loginctl enable-linger $USER` so the broker survives logout.")
  println(`Web UI: ${flags.publicUrl}`)
  println(
    "The first browser to connect pairs automatically; headless: `supermux pair <name>`.",
  )

  return 0
}
