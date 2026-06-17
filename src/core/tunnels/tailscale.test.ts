import { test, expect } from "bun:test"
import { tailscaleProvider } from "./tailscale"
import type { ConnectCtx, Run, RunResult } from "./types"

// Canned `tailscale status --json` blob — note the trailing dot on DNSName,
// which the provider must strip.
const STATUS_JSON = JSON.stringify({ Self: { DNSName: "box.tailnet.ts.net." } })

/** A fake Run that records every argv and answers per a small route table. */
function fakeRun(routes: (argv: string[]) => Partial<RunResult> | undefined): {
  run: Run
  calls: string[][]
} {
  const calls: string[][] = []
  const run: Run = async (argv) => {
    calls.push(argv)
    const r = routes(argv) ?? {}
    return { code: r.code ?? 0, stdout: r.stdout ?? "", stderr: r.stderr ?? "" }
  }
  return { run, calls }
}

/** Default route table: `status --json` returns the canned blob, all else ok. */
function statusRoutes(argv: string[]): Partial<RunResult> | undefined {
  if (argv[1] === "status") return { stdout: STATUS_JSON }
  return {}
}

/** Minimal non-interactive ConnectCtx (tty:false, yes:true). */
function makeCtx(run: Run, over: Partial<ConnectCtx> = {}): ConnectCtx {
  return {
    port: "8787",
    stateDir: "/tmp/mux-test",
    tty: false,
    yes: true,
    run,
    println: () => {},
    ask: async () => null,
    confirm: async (_p, def) => def,
    ...over,
  }
}

test("identity: id/label/bin and stable default mode", () => {
  expect(tailscaleProvider.id).toBe("tailscale")
  expect(tailscaleProvider.label).toBe("Tailscale")
  expect(tailscaleProvider.bin).toBe("tailscale")
  expect(tailscaleProvider.modes[0]!.id).toBe("serve")
  expect(tailscaleProvider.modes[0]!.stable).toBe(true)
  expect(tailscaleProvider.modes[1]!.id).toBe("funnel")
  expect(tailscaleProvider.modes.every((m) => m.stable)).toBe(true)
})

test("up (serve, default mode) builds serve argv and returns the stable tailnet URL", async () => {
  const { run, calls } = fakeRun(statusRoutes)
  const res = await tailscaleProvider.up(makeCtx(run)) // no mode ⇒ default serve

  expect(calls).toContainEqual(["tailscale", "serve", "--bg", "8787"])
  expect(calls).toContainEqual(["tailscale", "status", "--json"])
  expect(res.publicUrl).toBe("https://box.tailnet.ts.net")
  expect(res.stable).toBe(true)
  expect(res.notes).toEqual([
    "Only devices on your tailnet can reach this — the phone/laptop you open it on must also run Tailscale.",
  ])
})

test("up never invokes funnel argv in serve mode", async () => {
  const { run, calls } = fakeRun(statusRoutes)
  await tailscaleProvider.up(makeCtx(run))
  expect(calls.some((c) => c[1] === "funnel")).toBe(false)
})

test("up (funnel) builds funnel argv and returns the public-https note", async () => {
  const { run, calls } = fakeRun(statusRoutes)
  const res = await tailscaleProvider.up(makeCtx(run, { mode: "funnel" }))

  expect(calls).toContainEqual(["tailscale", "funnel", "--bg", "8787"])
  expect(calls.some((c) => c[1] === "serve")).toBe(false)
  expect(res.publicUrl).toBe("https://box.tailnet.ts.net")
  expect(res.stable).toBe(true)
  expect(res.notes).toEqual(["Publicly reachable over HTTPS via Tailscale Funnel."])
})

test("up honors a custom port", async () => {
  const { run, calls } = fakeRun(statusRoutes)
  await tailscaleProvider.up(makeCtx(run, { port: "9000" }))
  expect(calls).toContainEqual(["tailscale", "serve", "--bg", "9000"])
})

test("up throws a clear error when the hostname can't be parsed", async () => {
  const { run } = fakeRun((argv) => (argv[1] === "status" ? { stdout: "not json" } : {}))
  await expect(tailscaleProvider.up(makeCtx(run))).rejects.toThrow(
    "could not resolve the Tailscale hostname",
  )
})

test("up throws when Self.DNSName is missing", async () => {
  const { run } = fakeRun((argv) => (argv[1] === "status" ? { stdout: "{}" } : {}))
  await expect(tailscaleProvider.up(makeCtx(run))).rejects.toThrow(
    "could not resolve the Tailscale hostname",
  )
})

test("login runs `tailscale up`, surfaces output, and maps exit code → boolean", async () => {
  const lines: string[] = []
  const { run, calls } = fakeRun(() => ({
    code: 0,
    stdout: "To authenticate, visit:\n  https://login.tailscale.com/a/abc123",
  }))
  const ok = await tailscaleProvider.login(makeCtx(run, { println: (s) => lines.push(s) }))

  expect(calls).toContainEqual(["tailscale", "up"])
  expect(ok).toBe(true)
  expect(lines.join("\n")).toContain("https://login.tailscale.com/a/abc123")
})

test("login returns false on a non-zero exit", async () => {
  const { run } = fakeRun(() => ({ code: 1, stderr: "needs login" }))
  expect(await tailscaleProvider.login(makeCtx(run))).toBe(false)
})

test("install with --yes (consent assumed) runs the platform installer", async () => {
  const { run, calls } = fakeRun(() => ({ code: 0 }))
  const ok = await tailscaleProvider.install(makeCtx(run, { yes: true }))

  expect(ok).toBe(true)
  if (process.platform === "darwin") {
    expect(calls).toContainEqual(["brew", "install", "tailscale"])
  } else if (process.platform === "linux") {
    expect(calls).toContainEqual([
      "sh",
      "-c",
      "curl -fsSL https://tailscale.com/install.sh | sh",
    ])
  }
})

test("install returns false (no spawn) when the user declines and does not throw", async () => {
  const { run, calls } = fakeRun(() => ({ code: 0 }))
  const ctx = makeCtx(run, { yes: false, confirm: async () => false })
  const ok = await tailscaleProvider.install(ctx)
  expect(ok).toBe(false)
  expect(calls.length).toBe(0)
})

test("install returns false (never throws) when the installer fails", async () => {
  if (process.platform !== "linux" && process.platform !== "darwin") return
  const { run } = fakeRun(() => ({ code: 1, stderr: "boom" }))
  const ok = await tailscaleProvider.install(makeCtx(run, { yes: true }))
  expect(ok).toBe(false)
})

test("down best-effort resets serve and turns funnel off, swallowing errors", async () => {
  const { run, calls } = fakeRun(() => ({ code: 1, stderr: "no serve config" }))
  await expect(tailscaleProvider.down(makeCtx(run))).resolves.toBeUndefined()
  expect(calls).toContainEqual(["tailscale", "serve", "reset"])
  expect(calls).toContainEqual(["tailscale", "funnel", "--bg", "off"])
})

test("status reports up + parsed URL from `tailscale status --json`", async () => {
  const { run, calls } = fakeRun(statusRoutes)
  const s = await tailscaleProvider.status(makeCtx(run))
  expect(calls).toContainEqual(["tailscale", "status", "--json"])
  expect(s.up).toBe(true)
  expect(s.url).toBe("https://box.tailnet.ts.net")
})

test("status reports down + no URL when the CLI errors", async () => {
  const { run } = fakeRun(() => ({ code: 1, stdout: "" }))
  const s = await tailscaleProvider.status(makeCtx(run))
  expect(s.up).toBe(false)
  expect(s.url).toBeUndefined()
})
