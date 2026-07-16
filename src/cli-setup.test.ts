import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { installLaunchdAgent, runSetupCommand } from "./cli-setup"

// ── helpers ──────────────────────────────────────────────────────────────────

/** Collect lines emitted by println into an array. */
function collector(): { lines: string[]; println: (s: string) => void } {
  const lines: string[] = []
  return { lines, println: (s) => lines.push(s) }
}

/**
 * Per-test sandbox. We point both MUX_HOME and MUX_STATE_DIR at tmp dirs and
 * isolate HOME so even if a unit *were* written it lands in the sandbox, never
 * in the real ~/.config. runSetupCommand re-reads these envs (it does NOT rely
 * on the import-time STATE_DIR constant), so setting them per-test is enough.
 */
let home: string
let muxHome: string
let stateDir: string
let saved: Record<string, string | undefined>

const ENV_KEYS = ["HOME", "MUX_HOME", "MUX_STATE_DIR"] as const

beforeEach(() => {
  const base = mkdtempSync(join(tmpdir(), "smx-setup-"))
  home = join(base, "home")
  muxHome = join(base, "mux")
  stateDir = join(muxHome, "state")
  saved = {}
  for (const k of ENV_KEYS) saved[k] = process.env[k]
  process.env.HOME = home
  process.env.MUX_HOME = muxHome
  process.env.MUX_STATE_DIR = stateDir
})

afterEach(() => {
  for (const k of ENV_KEYS) {
    if (saved[k] === undefined) delete process.env[k]
    else process.env[k] = saved[k]
  }
})

const envPath = () => join(stateDir, ".env")
const unitPath = () => join(home, ".config", "systemd", "user", "supermux.service")

/** Parse a .env file into a key→value map. */
function readEnvMap(): Record<string, string> {
  const map: Record<string, string> = {}
  for (const line of readFileSync(envPath(), "utf8").split("\n")) {
    const m = line.match(/^(\w+)=(.*)$/)
    if (m) map[m[1]!] = m[2]!
  }
  return map
}

// ── fresh install ────────────────────────────────────────────────────────────
describe("runSetupCommand (fresh)", () => {
  test("writes .env with default port + public URL, mode 0600, exits 0", async () => {
    const { lines, println } = collector()
    const code = await runSetupCommand(["--no-service"], println)
    expect(code).toBe(0)
    expect(existsSync(envPath())).toBe(true)

    const map = readEnvMap()
    expect(map.MUX_WEB_PORT).toBe("8787")
    expect(map.MUX_WEB_PUBLIC_URL).toBe("http://localhost:8787")
    expect(map.MUX_RELAY_DOMAIN).toBe("relay.supermux.dev")
    // no token given → key absent
    expect(map.MUX_TELEGRAM_BOT_TOKEN).toBeUndefined()

    // 0600 exactly
    expect(statSync(envPath()).mode & 0o777).toBe(0o600)

    // println reports the keys it added
    const out = lines.join("\n")
    expect(out).toContain("MUX_WEB_PORT")
    expect(out).toContain("added")
  })

  test("prints an agent-CLI report (one line per known CLI)", async () => {
    const { lines, println } = collector()
    await runSetupCommand(["--no-service"], println)
    const out = lines.join("\n")
    for (const cli of ["claude", "codex", "cursor-agent", "opencode"]) {
      expect(out).toContain(cli)
    }
  })

  test("prints the web URL and the linger hint", async () => {
    const { lines, println } = collector()
    await runSetupCommand(["--no-service"], println)
    const out = lines.join("\n")
    expect(out).toContain("http://localhost:8787")
    expect(out).toContain("enable-linger")
  })

  test("the linger hint resolves a real username and never prints a literal $USER", async () => {
    const { username } = await import("./shared/home")
    const { lines, println } = collector()
    await runSetupCommand(["--no-service"], println)
    const out = lines.join("\n")
    // The user shouldn't have to hand-edit a copy-pasted command — it must carry
    // their actual username, not the unexpanded shell variable.
    expect(out).not.toContain("$USER")
    expect(out).toContain(`enable-linger ${username()}`)
  })
})

// ── idempotency: never clobber existing values ────────────────────────────────
describe("runSetupCommand (existing .env)", () => {
  test("keeps a user's custom MUX_WEB_PORT=9999 instead of overwriting", async () => {
    // Seed an existing .env with a custom port BEFORE running setup.
    const { mkdirSync } = await import("fs")
    mkdirSync(stateDir, { recursive: true, mode: 0o700 })
    writeFileSync(envPath(), "MUX_WEB_PORT=9999\n", { mode: 0o600 })

    const { lines, println } = collector()
    const code = await runSetupCommand(["--no-service"], println)
    expect(code).toBe(0)

    const map = readEnvMap()
    // The custom value SURVIVES — not reset to 8787.
    expect(map.MUX_WEB_PORT).toBe("9999")
    // The missing key was appended.
    expect(map.MUX_WEB_PUBLIC_URL).toBe("http://localhost:8787")

    const out = lines.join("\n")
    expect(out).toContain("kept")
    expect(out).toContain("MUX_WEB_PORT")
  })
})

// ── --no-service ──────────────────────────────────────────────────────────────
describe("runSetupCommand (--no-service)", () => {
  test("does not write a systemd unit", async () => {
    const { println } = collector()
    await runSetupCommand(["--no-service"], println)
    expect(existsSync(unitPath())).toBe(false)
  })
})

// ── source mode (IS_COMPILED=false under bun test) ────────────────────────────
describe("runSetupCommand (source mode, no --no-service)", () => {
  test("skips unit creation with an explanatory message, writes no unit", async () => {
    // Under bun test IS_COMPILED is false. Without --no-service the unit step
    // is reached but must SKIP (and NOT invoke systemctl) because the unit
    // targets the compiled binary.
    const { lines, println } = collector()
    const code = await runSetupCommand([], println)
    expect(code).toBe(0)
    expect(existsSync(unitPath())).toBe(false)

    const out = lines.join("\n")
    // explanatory note about source mode / compiled binary
    expect(out.toLowerCase()).toContain("source")
  })
})

// ── flags ─────────────────────────────────────────────────────────────────────
describe("runSetupCommand (flags)", () => {
  test("--port/--public-url/--telegram-token all land in a fresh .env", async () => {
    const { println } = collector()
    const code = await runSetupCommand(
      [
        "--no-service",
        "--port",
        "9999",
        "--public-url",
        "https://x.example",
        "--telegram-token",
        "abc:123",
      ],
      println,
    )
    expect(code).toBe(0)

    const map = readEnvMap()
    expect(map.MUX_WEB_PORT).toBe("9999")
    expect(map.MUX_WEB_PUBLIC_URL).toBe("https://x.example")
    expect(map.MUX_TELEGRAM_BOT_TOKEN).toBe("abc:123")
    expect(map.MUX_RELAY_DOMAIN).toBe("relay.supermux.dev")
  })

  test("--no-relay records a durable opt-out", async () => {
    const { println } = collector()
    await runSetupCommand(["--no-service"], println)
    await runSetupCommand(["--no-service", "--no-relay"], println)
    expect(readEnvMap().MUX_RELAY_DOMAIN).toBe("")

    await runSetupCommand(["--no-service"], println)
    expect(readEnvMap().MUX_RELAY_DOMAIN).toBe("")

    await runSetupCommand(["--no-service", "--relay-domain", "relay.supermux.dev"], println)
    expect(readEnvMap().MUX_RELAY_DOMAIN).toBe("relay.supermux.dev")
  })
})

// ── hard failure ──────────────────────────────────────────────────────────────
describe("runSetupCommand (state dir failure)", () => {
  test("returns 1 when STATE_DIR cannot be created", async () => {
    // Point MUX_STATE_DIR at a path whose parent is a *file*, so mkdir fails.
    const filePath = join(home, "iam-a-file")
    const { mkdirSync } = await import("fs")
    mkdirSync(home, { recursive: true })
    writeFileSync(filePath, "x")
    process.env.MUX_STATE_DIR = join(filePath, "state")

    const { lines, println } = collector()
    const code = await runSetupCommand(["--no-service"], println)
    expect(code).toBe(1)
    expect(lines.join("\n").toLowerCase()).toContain("error")
  })
})

// ── macOS LaunchAgent (installLaunchdAgent) ───────────────────────────────────
// Skipped on a real macOS host: there launchctl exists and the function would
// actually bootstrap a LaunchAgent into the user's gui domain (a side effect we
// don't want in a test run). On Linux CI launchctl is absent, so it just writes
// the plist + reports gracefully — which is exactly what we want to assert.
describe("installLaunchdAgent (macOS service)", () => {
  test.skipIf(process.platform === "darwin")(
    "writes a LaunchAgent plist (label, RunAtLoad, baked PATH) and reports gracefully without launchctl",
    () => {
      const { lines, println } = collector()
      installLaunchdAgent(
        {
          port: "8787",
          publicUrl: "http://localhost:8787",
          relayDomain: "relay.supermux.dev",
          relayDomainExplicit: false,
          noService: false,
          forceSourceUnit: false,
        },
        println,
      )

      const plist = join(home, "Library", "LaunchAgents", "dev.supermux.broker.plist")
      expect(existsSync(plist)).toBe(true)

      const xml = readFileSync(plist, "utf8")
      expect(xml).toContain("<key>Label</key>")
      expect(xml).toContain("dev.supermux.broker")
      expect(xml).toContain("<key>RunAtLoad</key>")
      // PATH is baked in so the launchd-spawned broker can find the agent CLIs.
      expect(xml).toContain("<key>PATH</key>")

      // launchctl is absent on Linux CI → no throw, and it reports the plist path.
      const out = lines.join("\n").toLowerCase()
      expect(out).toContain("library/launchagents")
    },
  )
})
