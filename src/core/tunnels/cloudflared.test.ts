import { test, expect, mock } from "bun:test"
import { cloudflaredProvider } from "./cloudflared"
import { which } from "./run"
import type { ConnectCtx, RunResult } from "./types"

// ── test harness ──────────────────────────────────────────────────────────────
// A recording fake Run + a minimal ConnectCtx. No real processes, no network:
// the fake records every argv and returns canned output keyed by a substring of
// the command, so each test asserts the EXACT commands a mode builds.

type Canned = { match: RegExp; result: Partial<RunResult> }

/** Build a ConnectCtx whose `run` records argv and replies from `canned`. */
function makeCtx(
  over: Partial<ConnectCtx> = {},
  canned: Canned[] = [],
): { ctx: ConnectCtx; calls: string[][]; out: string[]; opts: Array<Parameters<ConnectCtx["run"]>[1]> } {
  const calls: string[][] = []
  const out: string[] = []
  const opts: Array<Parameters<ConnectCtx["run"]>[1]> = []
  const ctx: ConnectCtx = {
    port: "8787",
    stateDir: "/tmp/mux-test",
    tty: false,
    yes: true,
    run: async (argv, o) => {
      calls.push(argv)
      opts.push(o)
      const hit = canned.find((c) => c.match.test(argv.join(" ")))
      return { code: 0, stdout: "", stderr: "", ...hit?.result }
    },
    println: (s) => out.push(s),
    // No TTY in tests: ask declines, confirm falls back to its default.
    ask: async () => null,
    confirm: async (_p, def) => def,
    ...over,
  }
  return { ctx, calls, out, opts }
}

// ── modes / identity ───────────────────────────────────────────────────────────

test("identity + modes: named is the default and is stable; quick is unstable", () => {
  expect(cloudflaredProvider.id).toBe("cloudflared")
  expect(cloudflaredProvider.bin).toBe("cloudflared")
  expect(cloudflaredProvider.label).toBe("Cloudflare Tunnel")
  expect(cloudflaredProvider.modes[0]!.id).toBe("named")
  expect(cloudflaredProvider.modes[0]!.stable).toBe(true)
  expect(cloudflaredProvider.modes[1]!.id).toBe("quick")
  expect(cloudflaredProvider.modes[1]!.stable).toBe(false)
})

// ── up(): named ────────────────────────────────────────────────────────────────

test("named: builds create + route dns from publicUrlHint and returns https://<host> stable", async () => {
  const { ctx, calls } = makeCtx({
    mode: "named",
    publicUrlHint: "https://mux.example.com/whatever",
  })

  const res = await cloudflaredProvider.up(ctx)

  expect(res).toEqual({ publicUrl: "https://mux.example.com", stable: true })
  // The hostname was extracted from the URL hint (path stripped).
  expect(calls).toContainEqual(["cloudflared", "tunnel", "create", "supermux"])
  expect(calls).toContainEqual([
    "cloudflared",
    "tunnel",
    "route",
    "dns",
    "supermux",
    "mux.example.com",
  ])
  // Best-effort service install is attempted.
  expect(calls).toContainEqual(["cloudflared", "service", "install"])
})

test("named: accepts a bare host hint", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" })
  const res = await cloudflaredProvider.up(ctx)
  expect(res.publicUrl).toBe("https://mux.example.com")
  expect(calls).toContainEqual([
    "cloudflared",
    "tunnel",
    "route",
    "dns",
    "supermux",
    "mux.example.com",
  ])
})

test("named: prompts for the hostname when no hint is given", async () => {
  let asked = 0
  const { ctx, calls } = makeCtx({
    mode: "named",
    ask: async () => {
      asked++
      return "asked.example.com"
    },
  })
  const res = await cloudflaredProvider.up(ctx)
  expect(asked).toBe(1)
  expect(res.publicUrl).toBe("https://asked.example.com")
  expect(calls).toContainEqual([
    "cloudflared",
    "tunnel",
    "route",
    "dns",
    "supermux",
    "asked.example.com",
  ])
})

test("named: tolerates an 'already exists' tunnel (re-run) without throwing", async () => {
  const { ctx } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    {
      match: /tunnel create supermux/,
      result: { code: 1, stderr: "tunnel with name supermux already exists" },
    },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.stable).toBe(true)
})

test("named: prints the manual run command when service install fails", async () => {
  const { ctx, out } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    { match: /service install/, result: { code: 1, stderr: "permission denied" } },
  ])
  await cloudflaredProvider.up(ctx)
  expect(out.join("\n")).toContain("cloudflared tunnel run supermux")
})

test("named: throws when no hostname can be resolved (no hint, ask returns null)", async () => {
  const { ctx } = makeCtx({ mode: "named", ask: async () => null })
  await expect(cloudflaredProvider.up(ctx)).rejects.toThrow(/hostname/i)
})

// ── up(): quick ─────────────────────────────────────────────────────────────────

test("quick: launches a DETACHED tunnel and polls its log for the URL (unstable + caveat)", async () => {
  const fakeLog =
    "2024 INF Request custom tunnel\n" +
    "2024 INF |  https://random-words-here.trycloudflare.com  |\n"
  // The URL is read by polling the log via `cat`, not by scrape-then-kill.
  const { ctx, calls } = makeCtx({ mode: "quick" }, [{ match: /cat /, result: { stdout: fakeLog } }])

  const res = await cloudflaredProvider.up(ctx)

  expect(res.publicUrl).toBe("https://random-words-here.trycloudflare.com")
  expect(res.stable).toBe(false)
  expect(res.notes?.[0]).toMatch(/throwaway/i)
  // CRITICAL: the tunnel is launched DETACHED (nohup, backgrounded) so it SURVIVES
  // this short-lived CLI — the live smoke proved scrape-then-kill leaves a dead URL.
  expect(calls[0]![0]).toBe("sh")
  expect(calls[0]![2]).toContain("nohup cloudflared tunnel")
  expect(calls[0]![2]).toContain("http://localhost:8787")
  expect(calls[0]![2]).toContain("cloudflared-quick.pid")
})

// ── login ─────────────────────────────────────────────────────────────────────

test("login: streams `tunnel login` live for named mode (browser auth URL must show immediately)", async () => {
  const { ctx, calls, opts } = makeCtx({ mode: "named" })
  const ok = await cloudflaredProvider.login(ctx)
  expect(ok).toBe(true)
  expect(calls).toContainEqual(["cloudflared", "tunnel", "login"])
  const i = calls.findIndex((c) => c.join(" ") === "cloudflared tunnel login")
  expect(opts[i]?.stream).toBe(true)
})

test("login: no-ops (no login) for quick mode", async () => {
  const { ctx, calls } = makeCtx({ mode: "quick" })
  const ok = await cloudflaredProvider.login(ctx)
  expect(ok).toBe(true)
  expect(calls).toEqual([])
})

// ── down (idempotent teardown) ──────────────────────────────────────────────────

test("down: kills a detached quick tunnel, then best-effort uninstall + cleanup, swallows errors", async () => {
  const { ctx, calls } = makeCtx({}, [
    { match: /service uninstall/, result: { code: 1, stderr: "not installed" } },
    { match: /tunnel cleanup/, result: { code: 1, stderr: "nothing to clean" } },
  ])
  await expect(cloudflaredProvider.down(ctx)).resolves.toBeUndefined()
  // First: stop a detached quick tunnel via its pidfile.
  expect(calls[0]![0]).toBe("sh")
  expect(calls[0]![2]).toContain("cloudflared-quick.pid")
  // Then the named-tunnel teardown.
  expect(calls).toContainEqual(["cloudflared", "service", "uninstall"])
  expect(calls).toContainEqual(["cloudflared", "tunnel", "cleanup", "supermux"])
})

// ── status ──────────────────────────────────────────────────────────────────────

test("status: up when `tunnel list` succeeds and mentions supermux", async () => {
  const { ctx } = makeCtx({}, [
    { match: /tunnel list/, result: { code: 0, stdout: "ID  NAME\nabc supermux\n" } },
  ])
  expect(await cloudflaredProvider.status(ctx)).toEqual({ up: true })
})

test("status: down when the tunnel isn't listed", async () => {
  const { ctx } = makeCtx({}, [
    { match: /tunnel list/, result: { code: 0, stdout: "ID  NAME\nxyz other\n" } },
  ])
  expect(await cloudflaredProvider.status(ctx)).toEqual({ up: false })
})

// ── detect / install ────────────────────────────────────────────────────────────

test("detect: mirrors the real `which('cloudflared')` (host-independent)", async () => {
  // No mocking of PATH — detect is just a `which`. Assert it agrees with the
  // same helper, so this passes whether or not cloudflared is installed here.
  const { ctx } = makeCtx()
  expect(await cloudflaredProvider.detect(ctx)).toBe(which("cloudflared"))
})

test("install: short-circuits to true when cloudflared is already on PATH", async () => {
  if (!which("cloudflared")) return // only meaningful when it's actually present
  const { ctx, calls } = makeCtx()
  expect(await cloudflaredProvider.install(ctx)).toBe(true)
  expect(calls).toEqual([]) // nothing spawned — it's already there
})

// Force the "not installed on linux" path deterministically (this host may have
// cloudflared). We stub `which` → false via mock.module for THIS test, then
// restore the real module so nothing leaks into other tests. Runs last.
test("install: on linux WITHOUT cloudflared, prints the docs URL and returns false", async () => {
  if (process.platform === "darwin") return // brew path is the darwin branch
  const real = await import("./run")
  mock.module("./run", () => ({ ...real, which: () => false }))
  try {
    const { ctx, out } = makeCtx() // yes:true ⇒ consent assumed, no prompt
    const ok = await cloudflaredProvider.install(ctx)
    expect(ok).toBe(false)
    expect(out.join("\n")).toContain("developers.cloudflare.com/cloudflare-tunnel/downloads")
  } finally {
    mock.module("./run", () => real) // restore for any later test runs
  }
})
