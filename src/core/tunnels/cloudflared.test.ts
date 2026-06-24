import { test, expect, mock } from "bun:test"
import {
  cloudflaredProvider,
  baseDomainOf,
  buildTunnelConfig,
  parseTunnelId,
  linuxInstallScript,
  installHintLines,
} from "./cloudflared"
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

// Force the linux install paths deterministically (this host may have cloudflared
// and/or a package manager). We stub `which` via mock.module for THESE tests, then
// restore the real module so nothing leaks into other tests.
test("install: on linux with no package manager, prints working install links and returns false", async () => {
  if (process.platform === "darwin") return // brew path is the darwin branch
  const real = await import("./run")
  mock.module("./run", () => ({ ...real, which: () => false })) // nothing on PATH
  try {
    const { ctx, out } = makeCtx() // yes:true ⇒ consent assumed, no prompt
    const ok = await cloudflaredProvider.install(ctx)
    expect(ok).toBe(false)
    const text = out.join("\n")
    expect(text).toContain("https://pkg.cloudflare.com/")
    expect(text).not.toContain("cloudflare-tunnel/downloads") // the old dead 404 path
  } finally {
    mock.module("./run", () => real) // restore for any later test runs
  }
})

test("install: on linux with apt + no cloudflared, runs the official apt install script", async () => {
  if (process.platform === "darwin") return
  const real = await import("./run")
  // cloudflared absent; apt-get present ⇒ the apt branch runs.
  mock.module("./run", () => ({ ...real, which: (b: string) => b === "apt-get" }))
  try {
    const { ctx, calls } = makeCtx()
    const ok = await cloudflaredProvider.install(ctx)
    expect(ok).toBe(false) // the faked run doesn't really install ⇒ still absent
    const sh = calls.find((c) => c[0] === "sh" && c[2]!.includes("apt-get install -y cloudflared"))
    expect(sh).toBeTruthy()
    expect(sh![2]).toContain("https://pkg.cloudflare.com/cloudflare-main.gpg")
  } finally {
    mock.module("./run", () => real)
  }
})

// ── pure helpers ────────────────────────────────────────────────────────────────

test("baseDomainOf strips the leftmost label; a bare apex is unchanged", () => {
  expect(baseDomainOf("mux.example.com")).toBe("example.com")
  expect(baseDomainOf("example.com")).toBe("example.com")
  expect(baseDomainOf("a.b.example.com")).toBe("b.example.com")
})

test("buildTunnelConfig emits broker ingress, optional wildcard, and a catch-all", () => {
  const base = buildTunnelConfig({
    tunnelId: "t-1",
    credentialsFile: "/h/.cloudflared/t-1.json",
    port: "8787",
    host: "mux.example.com",
  })
  expect(base).toContain("tunnel: t-1")
  expect(base).toContain("credentials-file: /h/.cloudflared/t-1.json")
  expect(base).toContain("hostname: mux.example.com")
  expect(base).toContain("service: http://localhost:8787")
  expect(base).toContain("http_status:404")
  expect(base).toContain("Managed by supermux")
  expect(base).not.toContain("*.")

  const wild = buildTunnelConfig({ port: "8787", host: "mux.example.com", wildcardBase: "example.com" })
  expect(wild).toContain('hostname: "*.example.com"')
  expect(wild).toContain("tunnel: supermux") // falls back to the tunnel NAME when no id
  expect(wild).not.toContain("credentials-file:") // omitted when no creds path
})

test("parseTunnelId extracts a UUID, else undefined", () => {
  expect(parseTunnelId("Created tunnel supermux with id 11111111-2222-4333-8444-555555555555")).toBe(
    "11111111-2222-4333-8444-555555555555",
  )
  expect(parseTunnelId("no id here")).toBeUndefined()
})

test("linuxInstallScript(apt) uses Cloudflare's signed apt repo and installs non-interactively", () => {
  const s = linuxInstallScript("apt")
  expect(s).toContain("https://pkg.cloudflare.com/cloudflare-main.gpg")
  expect(s).toContain("https://pkg.cloudflare.com/cloudflared any main")
  expect(s).toContain("apt-get install -y cloudflared")
  expect(s).toContain('[ "$(id -u)" = 0 ] || SUDO=sudo') // sudo only when not root
})

test("linuxInstallScript(dnf/yum) drops the official .repo and installs", () => {
  for (const pm of ["dnf", "yum"] as const) {
    const s = linuxInstallScript(pm)
    expect(s).toContain("https://pkg.cloudflare.com/cloudflared.repo")
    expect(s).toContain(`${pm} install -y cloudflared`)
  }
})

test("installHintLines points at working URLs (not the dead docs path)", () => {
  const text = installHintLines().join("\n")
  expect(text).toContain("https://pkg.cloudflare.com/")
  expect(text).toContain("https://github.com/cloudflare/cloudflared/releases/latest")
  expect(text).not.toContain("cloudflare-tunnel/downloads")
})

// ── up(): named — ingress config + wildcard ─────────────────────────────────────

test("named: writes config.yml with the broker-host ingress rule (the 404 fix)", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    { match: /tunnel create/, result: { stdout: "Created tunnel supermux with id 11111111-1111-4111-8111-111111111111" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.publicUrl).toBe("https://mux.example.com")
  expect(res.proxyBaseDomain).toBeUndefined()
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write).toBeTruthy()
  expect(write![2]).toContain("hostname: mux.example.com")
  expect(write![2]).toContain("service: http://localhost:8787")
  expect(write![2]).toContain("http_status:404")
  expect(write![2]).toContain("11111111-1111-4111-8111-111111111111.json")
  expect(write![2]).not.toContain("*.")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "mux.example.com"])
  expect(calls).toContainEqual(["cloudflared", "service", "install"])
})

test("named: --wildcard routes *.base and adds a wildcard ingress rule, returns proxyBaseDomain", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com", wildcard: true }, [
    { match: /tunnel create/, result: { stdout: "id 22222222-2222-4222-8222-222222222222" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBe("example.com")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "*.example.com"])
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).toContain('hostname: "*.example.com"')
})

test("named: a --wildcard-domain overrides the derived base", async () => {
  const { ctx, calls } = makeCtx(
    { mode: "named", publicUrlHint: "mux.example.com", wildcard: true, wildcardDomain: "apps.example.com" },
    [{ match: /tunnel create/, result: { stdout: "id 22222222-2222-4222-8222-222222222222" } }],
  )
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBe("apps.example.com")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "*.apps.example.com"])
})

test("named: wildcard DNS failure keeps the broker host working and skips proxyBaseDomain", async () => {
  const { ctx, calls, out } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com", wildcard: true }, [
    { match: /tunnel create/, result: { stdout: "id 33333333-3333-4333-8333-333333333333" } },
    { match: /route dns supermux \*\./, result: { code: 1, stderr: "wildcard not allowed on this plan" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBeUndefined()
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).not.toContain("*.example.com")
  expect(out.join("\n")).toContain("path mode")
})

test("named: resolves the tunnel id from `tunnel list` when create says it already exists", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    { match: /tunnel create/, result: { code: 1, stderr: "tunnel with name supermux already exists" } },
    { match: /tunnel list --output json/, result: { stdout: '[{"id":"44444444-4444-4444-4444-444444444444","name":"supermux"}]' } },
  ])
  await cloudflaredProvider.up(ctx)
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).toContain("credentials-file:")
  expect(write![2]).toContain("44444444-4444-4444-4444-444444444444.json")
})

test("named: rejects a hostname containing shell metacharacters / newlines", async () => {
  const { ctx } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com\nSUPERMUX_CFG\nwhoami" }, [
    { match: /tunnel create/, result: { stdout: "id 55555555-5555-4555-8555-555555555555" } },
  ])
  await expect(cloudflaredProvider.up(ctx)).rejects.toThrow(/invalid hostname/i)
})
