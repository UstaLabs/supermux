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
import { username } from "./shared/home"

// Agent CLIs we report on (PATH presence only — the user owns these logins).
const AGENT_CLIS = ["claude", "codex", "cursor-agent", "opencode", "grok"] as const

const UNIT_NAME = "supermux.service"
const LAUNCHD_LABEL = "dev.supermux.broker"

interface Flags {
  port: string
  publicUrl: string
  telegramToken?: string
  relayDomain: string
  relayDomainExplicit: boolean
  noService: boolean
  forceSourceUnit: boolean
}

function parseFlags(args: string[]): Flags {
  const flags: Flags = {
    port: "8787",
    publicUrl: "http://localhost:8787",
    relayDomain: "relay.supermux.dev",
    relayDomainExplicit: false,
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
      case "--relay-domain":
        flags.relayDomain = args[++i] ?? flags.relayDomain
        flags.relayDomainExplicit = true
        break
      case "--no-relay":
        flags.relayDomain = ""
        flags.relayDomainExplicit = true
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
function installSystemdUnit(_flags: Flags, println: (s: string) => void): void {
  // Detect a usable `systemctl --user`. (The shared source-mode guard lives in
  // the installService dispatcher, alongside the macOS LaunchAgent path.)
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
    enableLinger(println)
  } else {
    println("supermux service did not come up ✖")
    println("Check logs with: journalctl --user -u supermux -n 50")
  }
}

/**
 * Keep the broker alive across logout without making the user do anything.
 * Enabling linger for your OWN user needs no sudo on a normal systemd box, so we
 * just do it (best-effort). Only if the auto-run can't do we fall back to a
 * copy-pasteable command — with the REAL username, never a literal `$USER`.
 */
function enableLinger(println: (s: string) => void): void {
  const user = username()
  if (user) {
    try {
      if (Bun.spawnSync(["loginctl", "enable-linger", user]).exitCode === 0) {
        println("Enabled lingering so the broker survives logout ✔")
        return
      }
    } catch {
      // loginctl absent or refused — fall through to the manual hint.
    }
  }
  println(`If the broker should survive logout, run: loginctl enable-linger ${user || "$(whoami)"}`)
}

/** Escape the values we interpolate into the LaunchAgent plist XML. */
function xmlEscape(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
}

const PLIST_TEMPLATE = (
  execPath: string,
  servicePath: string,
  outLog: string,
  errLog: string,
): string =>
  `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LAUNCHD_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${execPath}</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
  <key>ProcessType</key>
  <string>Background</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>NODE_ENV</key>
    <string>production</string>
    <key>PATH</key>
    <string>${servicePath}</string>
  </dict>
  <key>StandardOutPath</key>
  <string>${outLog}</string>
  <key>StandardErrorPath</key>
  <string>${errLog}</string>
</dict>
</plist>
`

/**
 * Install + load the user LaunchAgent (the macOS analogue of installSystemdUnit).
 * Writes ~/Library/LaunchAgents/dev.supermux.broker.plist pointing at this
 * binary, then bootstraps/enables/kickstarts it via launchctl. Any launchctl
 * failure is reported via println, never thrown — setup keeps going, returns 0.
 *
 * The plist bakes in a PATH (the user's current PATH ∪ Homebrew/npm-global/local
 * bins) because launchd's default PATH is a bare /usr/bin:/bin — which hides
 * /opt/homebrew/bin etc., where the agent CLIs live. Without this the broker's
 * agent-CLI preflight fails and spawned sessions can't find claude/codex.
 *
 * Exported for unit testing (the plist-writing path); the dispatcher chooses it
 * over installSystemdUnit only on darwin.
 */
export function installLaunchdAgent(_flags: Flags, println: (s: string) => void): void {
  const home = process.env.HOME || homedir()
  const agentsDir = join(home, "Library", "LaunchAgents")
  const plistFile = join(agentsDir, `${LAUNCHD_LABEL}.plist`)
  const stateDir = resolveStateDir()
  const outLog = join(stateDir, "supermux.out.log")
  const errLog = join(stateDir, "supermux.err.log")

  const servicePath = [
    process.env.PATH,
    "/opt/homebrew/bin",
    "/usr/local/bin",
    join(home, ".local", "bin"),
  ]
    .filter(Boolean)
    .join(":")

  // 1. Write the plist (+ ensure the log dir exists so launchd can open the logs).
  try {
    mkdirSync(agentsDir, { recursive: true })
    mkdirSync(stateDir, { recursive: true })
    writeFileSync(
      plistFile,
      PLIST_TEMPLATE(
        xmlEscape(process.execPath),
        xmlEscape(servicePath),
        xmlEscape(outLog),
        xmlEscape(errLog),
      ),
    )
    println(`Wrote ${plistFile}`)
  } catch (e) {
    println(`Could not write the LaunchAgent plist: ${String(e)}`)
    return
  }

  // 2. Need launchctl to load it. Absent (e.g. non-macOS) → plist is in place and
  //    will load on next login; nothing more we can do here.
  if (Bun.which("launchctl") === null) {
    println("launchctl not found; the plist is installed and will load on next login.")
    return
  }

  // 3. Idempotent (re)load in the user's GUI domain: bootout any prior instance
  //    (ignore failure — first run has none), then bootstrap + enable + kickstart.
  const uid = typeof process.getuid === "function" ? process.getuid() : 0
  const domain = `gui/${uid}`
  const target = `${domain}/${LAUNCHD_LABEL}`
  const lc = (sub: string[]): boolean => {
    try {
      return Bun.spawnSync(["launchctl", ...sub]).exitCode === 0
    } catch {
      return false
    }
  }

  lc(["bootout", target])
  if (!lc(["bootstrap", domain, plistFile])) {
    println("launchctl bootstrap reported an error; attempting to start it anyway.")
  }
  lc(["enable", target])
  lc(["kickstart", "-k", target])

  // 4. Give it a moment, then confirm it's loaded + running.
  Bun.sleepSync(2000)
  let running = false
  try {
    const r = Bun.spawnSync(["launchctl", "print", target])
    running =
      r.exitCode === 0 && new TextDecoder().decode(r.stdout).includes("state = running")
  } catch {
    running = false
  }
  if (running) {
    println("supermux LaunchAgent running ✔")
  } else {
    println("supermux LaunchAgent loaded; if it isn't up yet, check the logs:")
    println(`  tail -f ${errLog}`)
  }
}

/**
 * Install the always-on user service for this platform: a LaunchAgent on macOS,
 * a systemd --user unit elsewhere. The source-mode guard is shared here: the
 * service targets process.execPath, which is the installed binary when compiled
 * but `bun` in source mode (use SETUP.md's manual unit for source installs).
 */
function installService(flags: Flags, println: (s: string) => void): void {
  if (!IS_COMPILED && !flags.forceSourceUnit) {
    println(
      "Note: setup's service unit targets the compiled binary. This looks like a" +
        " source install — use SETUP.md's manual unit instead. Skipping service setup.",
    )
    return
  }
  if (process.platform === "darwin") installLaunchdAgent(flags, println)
  else installSystemdUnit(flags, println)
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
    ["MUX_RELAY_DOMAIN", flags.relayDomain],
  ]
  if (flags.telegramToken) desired.push(["MUX_TELEGRAM_BOT_TOKEN", flags.telegramToken])

  let changed = false
  for (const [k, v] of desired) {
    if (map.has(k)) {
      if (k === "MUX_RELAY_DOMAIN" && flags.relayDomainExplicit && map.get(k) !== v) {
        map.set(k, v)
        changed = true
        println(`${v ? "updated" : "disabled"} ${k}`)
      } else {
        println(`kept existing ${k}`)
      }
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

  // ── always-on service (systemd unit on Linux, LaunchAgent on macOS) ───────
  if (flags.noService) {
    println("Skipping service setup (--no-service).")
  } else {
    installService(flags, println)
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
  // When we actually installed the systemd service, installSystemdUnit already
  // enabled lingering for the user — so only hint here when we DIDN'T (--no-service
  // or a source install). And resolve the real username: a literal `$USER` is the
  // exact rough edge a user shouldn't have to hand-fix.
  const willManageService = !flags.noService && (IS_COMPILED || flags.forceSourceUnit)
  if (process.platform === "darwin") {
    println(
      "Headless Mac mini? Enable Automatic Login (System Settings ▸ Users & Groups) so" +
        " the LaunchAgent runs after a reboot — or install it as a system LaunchDaemon.",
    )
  } else if (!willManageService) {
    const user = username()
    println(
      `Headless/SSH? Run \`loginctl enable-linger ${user || "$(whoami)"}\` so the broker survives logout.`,
    )
  }
  println(`Web UI: ${flags.publicUrl}`)
  const configuredRelay = map.get("MUX_RELAY_DOMAIN")?.trim()
  println(
    configuredRelay
      ? `Remote access: enabled through ${configuredRelay}`
      : "Remote access: relay disabled (--no-relay)",
  )
  println(
    "The first browser to connect pairs automatically; headless: `supermux pair <name>`.",
  )

  return 0
}
