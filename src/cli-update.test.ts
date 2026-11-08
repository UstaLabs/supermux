import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { restartAfterSwap, runRollbackCommand, runUpdateCommand } from "./cli-update"

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

// ── runUpdateCommand([]) on a desktop-managed broker ─────────────────────────
describe("runUpdateCommand (managed mode)", () => {
  test("refuses to self-swap: prints the instruction, exits 0, never starts the apply flow", async () => {
    const saved = process.env.MUX_MANAGED_BY
    process.env.MUX_MANAGED_BY = "desktop"
    try {
      const { lines, println } = collector()
      const code = await runUpdateCommand([], println)
      expect(code).toBe(0)
      // The apply flow's first output is "checking versions.json…"; it must not run.
      expect(lines).toEqual(["Updated with the supermux app."])
    } finally {
      if (saved === undefined) delete process.env.MUX_MANAGED_BY
      else process.env.MUX_MANAGED_BY = saved
    }
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

// ── restartAfterSwap: who restarts the broker after an update or a rollback ───
// #29: a CLI that restarted the service itself (systemctl, launchctl) left the broker to a stop it could not tell from
// a plain one, so it shut its agents down: an agent that ran `supermux update` lost its turn. The broker restarts
// itself instead, as for `supermux connect --restart`, and a restart it scheduled leaves the agents running.
describe("restartAfterSwap", () => {
  function brokerAnswering(status: number, seen: string[] = []) {
    return (async (url: string, init?: RequestInit) => {
      seen.push(`${url} ${new Headers(init?.headers).get("x-supermux-stop-token")}`)
      return new Response(null, { status })
    }) as unknown as typeof fetch
  }
  function stateWithToken(): string {
    const dir = mkdtempSync(join(tmpdir(), "mux-update-restart-"))
    writeFileSync(join(dir, "stop-token"), "tok-9")
    writeFileSync(join(dir, ".env"), "MUX_WEB_PORT=9911\n")
    return dir
  }

  test("asks the running broker to restart itself, on its own port, says so, and restarts nothing from here", async () => {
    const seen: string[] = []
    const { lines, println } = collector()
    let local = 0
    const ok = await restartAfterSwap(println, { stateDir: stateWithToken(), fetchImpl: brokerAnswering(202, seen), restartService: () => { local++; return true } })
    expect(ok).toBe(true)
    expect(seen).toEqual(["http://127.0.0.1:9911/internal/restart tok-9"])
    expect(local).toBe(0)
    expect(lines).toEqual(["Asked the running broker to restart: its sessions keep running."])
  })

  test("a broker nothing would bring back (409) is not restarted from here either, and says what to do", async () => {
    const { lines, println } = collector()
    let local = 0
    const ok = await restartAfterSwap(println, { stateDir: stateWithToken(), fetchImpl: brokerAnswering(409), restartService: () => { local++; return true } })
    expect(ok).toBe(false)
    expect(local).toBe(0)
    expect(lines.join("\n")).toContain("restart it yourself")
  })

  test("a broker that cannot be asked (none answers, an older one) is restarted from here, as before", async () => {
    const down = (async () => { throw new TypeError("Unable to connect") }) as unknown as typeof fetch
    let local = 0
    const first = collector()
    expect(await restartAfterSwap(first.println, { stateDir: stateWithToken(), fetchImpl: down, restartService: () => { local++; return true } })).toBe(true)
    expect(first.lines).toEqual(["No running broker answered: restarted its service from here."])
    const second = collector()
    expect(await restartAfterSwap(second.println, { stateDir: stateWithToken(), fetchImpl: brokerAnswering(404), restartService: () => { local++; return false } })).toBe(false)
    expect(second.lines).toEqual([])
    expect(local).toBe(2)
  })
})
