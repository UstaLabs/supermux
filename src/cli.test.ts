import { describe, expect, test } from "bun:test"
import { existsSync, mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

function runCli(args: string[]): { code: number; stdout: string; stderr: string } {
  const r = Bun.spawnSync(["bun", "src/cli.ts", ...args], { cwd: import.meta.dirname + "/.." })
  return { code: r.exitCode, stdout: r.stdout.toString(), stderr: r.stderr.toString() }
}

describe("cli dispatcher", () => {
  test("version prints versionString and exits 0", () => {
    const r = runCli(["version"])
    expect(r.code).toBe(0)
    expect(r.stdout.trim()).toBe("dev (unknown)")
  })

  test("unknown subcommand exits 2 with usage on stderr", () => {
    const r = runCli(["frobnicate"])
    expect(r.code).toBe(2)
    expect(r.stderr).toContain("usage: supermux")
    expect(r.stderr).toContain("frobnicate")
  })

  test("pair without a name exits 1 with the script's own usage", () => {
    // proves argv shifting: scripts/pair.ts reads argv[2] as the device name
    const r = runCli(["pair"])
    expect(r.code).toBe(1)
    expect(r.stderr).toContain("usage: bun run pair <device-name>")
  })

  test("setup --no-service runs and exits 0 (writes .env to an isolated state dir)", () => {
    // Sandbox MUX_STATE_DIR/HOME so this never touches the real ~/.mux.
    const tmp = mkdtempSync(join(tmpdir(), "smx-cli-setup-"))
    const r = Bun.spawnSync(["bun", "src/cli.ts", "setup", "--no-service"], {
      cwd: import.meta.dirname + "/..",
      env: {
        ...process.env,
        HOME: join(tmp, "home"),
        MUX_HOME: join(tmp, "mux"),
        MUX_STATE_DIR: join(tmp, "mux", "state"),
      },
    })
    expect(r.exitCode).toBe(0)
    expect(existsSync(join(tmp, "mux", "state", ".env"))).toBe(true)
  })

  test("pair prefers the fresh setup's stable relay URL", () => {
    const tmp = mkdtempSync(join(tmpdir(), "smx-cli-relay-pair-"))
    const env = {
      ...process.env,
      HOME: join(tmp, "home"),
      MUX_HOME: join(tmp, "mux"),
      MUX_STATE_DIR: join(tmp, "mux", "state"),
    }
    const setup = Bun.spawnSync(["bun", "src/cli.ts", "setup", "--no-service"], {
      cwd: import.meta.dirname + "/..",
      env,
    })
    expect(setup.exitCode).toBe(0)

    const pair = Bun.spawnSync(["bun", "src/cli.ts", "pair", "phone"], {
      cwd: import.meta.dirname + "/..",
      env,
    })
    expect(pair.exitCode).toBe(0)
    expect(pair.stdout.toString()).toMatch(/https:\/\/h-[a-z2-7]{26}\.relay\.supermux\.dev\/pair\?t=/)
  })

    test("unknown subcommand usage lists setup", () => {
    const r = runCli(["frobnicate"])
    expect(r.stderr).toContain("setup")
  })

  test("credential get fills from STATE_DIR forge store", () => {
    const tmp = mkdtempSync(join(tmpdir(), "smx-cli-cred-"))
    const state = join(tmp, "mux", "state")
    // Seed a forge connection via a tiny bun script against the real store helpers.
    const seed = Bun.spawnSync([
      "bun", "-e",
      `
        import { mkdirSync } from "fs"
        import { join } from "path"
        import { openDb, runMigrations } from "./src/core/storage/db"
        import { MIGRATIONS } from "./src/core/storage/migrations"
        import { ForgeStore } from "./src/core/forge/store"
        mkdirSync(process.env.MUX_STATE_DIR!, { recursive: true })
        const db = openDb(join(process.env.MUX_STATE_DIR!, "db.sqlite3"))
        runMigrations(db, MIGRATIONS)
        new ForgeStore(db).add({
          id: "github:github.com:a", kind: "github", host: "github.com",
          apiBase: "https://api.github.com", label: "x", account: { login: "a" },
          source: "pat", transport: "https", status: "ok", token: "ghp_secret",
        })
      `,
    ], {
      cwd: import.meta.dirname + "/..",
      env: { ...process.env, MUX_STATE_DIR: state },
    })
    expect(seed.exitCode).toBe(0)
    const r = Bun.spawnSync(
      ["bun", "src/cli.ts", "credential", "github:github.com:a", "get"],
      {
        cwd: import.meta.dirname + "/..",
        env: { ...process.env, HOME: join(tmp, "home"), MUX_HOME: join(tmp, "mux"), MUX_STATE_DIR: state },
      },
    )
    expect(r.exitCode).toBe(0)
    expect(r.stdout.toString()).toBe("username=x-access-token\npassword=ghp_secret\n")
  })

  test("unknown subcommand usage lists credential", () => {
    const r = runCli(["frobnicate"])
    expect(r.stderr).toContain("credential")
  })
})
