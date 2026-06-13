import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { runRollbackCommand, runUpdateCommand } from "./cli-update"

// ── helpers ──────────────────────────────────────────────────────────────────

/** Collect lines emitted by println into an array. */
function collector(): { lines: string[]; println: (s: string) => void } {
  const lines: string[] = []
  return { lines, println: (s) => lines.push(s) }
}

// ── runUpdateCommand([]) in source mode ──────────────────────────────────────
describe("runUpdateCommand (source mode)", () => {
  test("exits 0 and prints 'Source install' message", async () => {
    // Under bun test, IS_COMPILED=false and no /.dockerenv → source mode.
    const { lines, println } = collector()
    const code = await runUpdateCommand([], println)
    expect(code).toBe(0)
    expect(lines.join("\n")).toContain("Source install")
  })
})

// ── runUpdateCommand(['--check']) with local HTTP server ─────────────────────
describe("runUpdateCommand --check", () => {
  let server: ReturnType<typeof Bun.serve>
  let origEnv: string | undefined

  beforeAll(async () => {
    // Build a minimal valid versions.json fixture for version 9.9.9
    const versionsJson = JSON.stringify({
      schemaVersion: 1,
      channels: {
        stable: {
          version: "9.9.9",
          publishedAt: new Date().toISOString(),
          notesUrl: "https://example.com/notes",
          assets: {
            "linux-x64": {
              url: "https://example.com/supermux-linux-x64",
              sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            },
            "linux-arm64": {
              url: "https://example.com/supermux-linux-arm64",
              sha256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            },
          },
        },
      },
    })

    server = Bun.serve({
      port: 0, // random free port
      fetch(req) {
        return new Response(versionsJson, {
          headers: { "content-type": "application/json" },
        })
      },
    })

    origEnv = process.env.MUX_UPDATE_URL
    process.env.MUX_UPDATE_URL = `http://127.0.0.1:${server.port}/versions.json`
  })

  afterAll(() => {
    server.stop()
    if (origEnv === undefined) {
      delete process.env.MUX_UPDATE_URL
    } else {
      process.env.MUX_UPDATE_URL = origEnv
    }
  })

  test("prints JSON with latest=9.9.9 and state=idle, exits 0", async () => {
    const { lines, println } = collector()
    const code = await runUpdateCommand(["--check"], println)
    expect(code).toBe(0)
    const output = lines.join("\n")
    const parsed = JSON.parse(output)
    expect(parsed.latest).toBe("9.9.9")
    expect(parsed.state).toBe("idle")
  })
})

// ── runRollbackCommand — no-prev path ─────────────────────────────────────────
describe("runRollbackCommand (no .prev)", () => {
  test("exits 1 and says 'Nothing to roll back' when no .prev exists", async () => {
    // bun's process.execPath directory won't have a .prev file, so rollback()
    // will return { ok: false, error: { kind: "no-prev" } }.
    const { lines, println } = collector()
    const code = await runRollbackCommand([], println)
    expect(code).toBe(1)
    expect(lines.join("\n")).toContain("Nothing to roll back")
  })
})

// ── spawn-based dispatcher wiring tests ──────────────────────────────────────
describe("cli dispatcher wiring (spawn)", () => {
  const ROOT = import.meta.dir + "/.."

  function runCli(
    args: string[],
  ): { code: number; stdout: string; stderr: string } {
    const r = Bun.spawnSync(["bun", "src/cli.ts", ...args], { cwd: ROOT })
    return {
      code: r.exitCode,
      stdout: r.stdout.toString(),
      stderr: r.stderr.toString(),
    }
  }

  test("'update' in source checkout exits 0 and prints 'Source install'", () => {
    const r = runCli(["update"])
    expect(r.code).toBe(0)
    expect(r.stdout + r.stderr).toContain("Source install")
  })

  test("'frobnicate' still exits 2 with usage listing update|rollback", () => {
    const r = runCli(["frobnicate"])
    expect(r.code).toBe(2)
    expect(r.stderr).toContain("update")
    expect(r.stderr).toContain("rollback")
  })
})
