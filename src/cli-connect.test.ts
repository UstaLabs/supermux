import { test, expect } from "bun:test"
import { mkdtempSync, readFileSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { runConnectCommand } from "./cli-connect"
import type { TunnelProvider, Run } from "./core/tunnels/types"

function tmp(): string {
  return mkdtempSync(join(tmpdir(), "mux-connect-"))
}
const okRun: Run = async () => ({ code: 0, stdout: "", stderr: "" })

/** A controllable in-memory provider (no real client/network). */
function fake(overrides: Partial<TunnelProvider> = {}): TunnelProvider {
  return {
    id: "cloudflared",
    label: "Fake",
    modes: [{ id: "named", label: "named", stable: true }],
    bin: undefined, // skip detect/install in the orchestrator
    async detect() { return true },
    async install() { return true },
    async login() { return true },
    async up() { return { publicUrl: "https://fake.example.com", stable: true } },
    async down() {},
    async status() { return { up: true } },
    ...overrides,
  }
}

test("happy path: writes .env, restarts the broker, prints a pair link", async () => {
  const dir = tmp()
  const out: string[] = []
  const calls: string[][] = []
  const run: Run = async (argv) => { calls.push(argv); return { code: 0, stdout: "", stderr: "" } }
  const code = await runConnectCommand(["cloudflared", "--yes"], {
    providers: [fake()], stateDir: dir, tty: false, run, println: (s) => out.push(s),
  })
  expect(code).toBe(0)
  expect(readFileSync(join(dir, ".env"), "utf8")).toContain("MUX_WEB_PUBLIC_URL=https://fake.example.com")
  expect(calls.some((c) => c.join(" ").includes("systemctl"))).toBe(true)
  expect(existsSync(join(dir, "devices.json"))).toBe(true)
  expect(out.join("\n")).toContain("https://fake.example.com/pair?t=")
})

test("--no-restart and --no-pair skip those steps", async () => {
  const dir = tmp()
  const calls: string[][] = []
  const run: Run = async (a) => { calls.push(a); return { code: 0, stdout: "", stderr: "" } }
  const code = await runConnectCommand(["cloudflared", "--yes", "--no-restart", "--no-pair"], {
    providers: [fake()], stateDir: dir, tty: false, run, println() {},
  })
  expect(code).toBe(0)
  expect(calls.some((c) => c.join(" ").includes("systemctl"))).toBe(false)
  expect(existsSync(join(dir, "devices.json"))).toBe(false)
})

test("ephemeral result prints the NOT-stable warning + caveat note", async () => {
  const dir = tmp()
  const out: string[] = []
  const p = fake({ async up() { return { publicUrl: "https://x.trycloudflare.com", stable: false, notes: ["⚠️ throwaway"] } } })
  await runConnectCommand(["cloudflared", "--yes"], {
    providers: [p], stateDir: dir, tty: false, run: okRun, println: (s) => out.push(s),
  })
  const text = out.join("\n")
  expect(text).toContain("NOT stable")
  expect(text).toContain("throwaway")
})

test("login failure leaves the broker unchanged (exit 1, no .env)", async () => {
  const dir = tmp()
  const code = await runConnectCommand(["cloudflared", "--yes"], {
    providers: [fake({ async login() { return false } })], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(code).toBe(1)
  expect(existsSync(join(dir, ".env"))).toBe(false)
})

test("up() throwing leaves the broker unchanged (exit 1)", async () => {
  const dir = tmp()
  const code = await runConnectCommand(["cloudflared", "--yes"], {
    providers: [fake({ async up() { throw new Error("boom") } })], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(code).toBe(1)
  expect(existsSync(join(dir, ".env"))).toBe(false)
})

test("manual with empty publicUrl is informational (no .env write)", async () => {
  const dir = tmp()
  const out: string[] = []
  const manual = fake({ id: "manual", async up() { return { publicUrl: "", stable: true, notes: ["point your proxy at 8787"] } } })
  const code = await runConnectCommand(["manual", "--yes"], {
    providers: [manual], stateDir: dir, tty: false, run: okRun, println: (s) => out.push(s),
  })
  expect(code).toBe(0)
  expect(existsSync(join(dir, ".env"))).toBe(false)
  expect(out.join("\n")).toContain("point your proxy")
})

test("--public-url is written through (manual)", async () => {
  const dir = tmp()
  const manual = fake({ id: "manual", async up(ctx) { return { publicUrl: ctx.publicUrlHint ?? "", stable: true } } })
  const code = await runConnectCommand(["manual", "--yes", "--public-url", "https://mine.example.com"], {
    providers: [manual], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(code).toBe(0)
  expect(readFileSync(join(dir, ".env"), "utf8")).toContain("MUX_WEB_PUBLIC_URL=https://mine.example.com")
})

test("invalid public URL is refused (exit 1, no write)", async () => {
  const dir = tmp()
  const code = await runConnectCommand(["cloudflared", "--yes"], {
    providers: [fake({ async up() { return { publicUrl: "not a url", stable: true } } })],
    stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(code).toBe(1)
  expect(existsSync(join(dir, ".env"))).toBe(false)
})

test("--off reverts to localhost", async () => {
  const dir = tmp()
  const code = await runConnectCommand(["--off", "--port", "8787"], {
    providers: [fake()], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(code).toBe(0)
  expect(readFileSync(join(dir, ".env"), "utf8")).toContain("MUX_WEB_PUBLIC_URL=http://localhost:8787")
})

test("interactive menu picks a provider by number", async () => {
  const dir = tmp()
  const code = await runConnectCommand([], {
    providers: [fake()], stateDir: dir, tty: true, ask: async () => "1", confirm: async () => true, run: okRun, println() {},
  })
  expect(code).toBe(0)
  expect(readFileSync(join(dir, ".env"), "utf8")).toContain("https://fake.example.com")
})

test("no provider + no TTY exits 2 with guidance", async () => {
  const dir = tmp()
  const out: string[] = []
  const code = await runConnectCommand([], { providers: [fake()], stateDir: dir, tty: false, run: okRun, println: (s) => out.push(s) })
  expect(code).toBe(2)
  expect(out.join("\n")).toContain("supermux connect cloudflared")
})

test("unknown provider exits 2", async () => {
  const dir = tmp()
  const code = await runConnectCommand(["bogus", "--yes"], { providers: [fake()], stateDir: dir, tty: false, run: okRun, println() {} })
  expect(code).toBe(2)
})

test("--status with no record says no tunnel", async () => {
  const dir = tmp()
  const out: string[] = []
  const code = await runConnectCommand(["--status"], { providers: [fake()], stateDir: dir, tty: false, run: okRun, println: (s) => out.push(s) })
  expect(code).toBe(0)
  expect(out.join("\n")).toContain("No tunnel configured")
})

test("--help returns 0 and prints usage", async () => {
  const out: string[] = []
  const code = await runConnectCommand(["--help"], { println: (s) => out.push(s) })
  expect(code).toBe(0)
  expect(out.join("\n")).toContain("supermux connect")
})
