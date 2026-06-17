import { test, expect } from "bun:test"
import { netbirdProvider } from "./netbird"
import type { ConnectCtx, Run } from "./types"

// A canned `netbird status -d` blob. The overlay IP lives on the "NetBird IP"
// line; everything else is noise the parser must skip.
const STATUS_BLOB = `OS: linux/amd64
Daemon version: 0.28.0
CLI version: 0.28.0
Management: Connected
Signal: Connected
Relays: 2/2 Available
NetBird IP: 100.110.111.246/16
Interface type: Kernel
Quantum resistance: false
Peers count: 3/3 Connected`

/** Build a minimal ConnectCtx with a fake `run`; records every argv it sees. */
function makeCtx(
  run: Run,
  over: Partial<ConnectCtx> = {},
): { ctx: ConnectCtx; out: string[] } {
  const out: string[] = []
  const ctx: ConnectCtx = {
    port: "8787",
    stateDir: "/tmp/mux-test",
    tty: false,
    yes: true,
    run,
    println: (s) => out.push(s),
    ask: async () => null,
    confirm: async (_p, def) => def,
    ...over,
  }
  return { ctx, out }
}

/** A fake `run` that returns a canned result per command and records calls. */
function fakeRun(
  handler: (argv: string[]) => { code?: number; stdout?: string; stderr?: string },
): { run: Run; calls: string[][] } {
  const calls: string[][] = []
  const run: Run = async (argv) => {
    calls.push(argv)
    const r = handler(argv)
    return { code: r.code ?? 0, stdout: r.stdout ?? "", stderr: r.stderr ?? "" }
  }
  return { run, calls }
}

test("provider identity matches the contract", () => {
  expect(netbirdProvider.id).toBe("netbird")
  expect(netbirdProvider.bin).toBe("netbird")
  expect(netbirdProvider.label).toBe("NetBird (private mesh)")
  expect(netbirdProvider.modes).toHaveLength(1)
  expect(netbirdProvider.modes[0]).toMatchObject({ id: "mesh", stable: true })
  expect(netbirdProvider.modes[0]!.label).toContain("NetBird mesh")
})

test("up() resolves the overlay IP from `netbird status -d` and builds the http URL", async () => {
  const { run, calls } = fakeRun((argv) =>
    argv[1] === "status" ? { stdout: STATUS_BLOB } : {},
  )
  const { ctx } = makeCtx(run)

  const res = await netbirdProvider.up(ctx)

  expect(res.publicUrl).toBe("http://100.110.111.246:8787")
  expect(res.stable).toBe(true)
  expect(res.notes).toHaveLength(2)
  expect(res.notes![0]).toContain("your phone must run the NetBird app")
  expect(res.notes![1]).toContain("listens on 127.0.0.1 by default")
  // Detailed status was enough — no fallback to plain `netbird status`.
  expect(calls).toEqual([["netbird", "status", "-d"]])
})

test("up() honors a non-default port", async () => {
  const { run } = fakeRun(() => ({ stdout: STATUS_BLOB }))
  const { ctx } = makeCtx(run, { port: "9999" })
  const res = await netbirdProvider.up(ctx)
  expect(res.publicUrl).toBe("http://100.110.111.246:9999")
})

test("up() falls back to plain `netbird status` when -d has no overlay IP", async () => {
  const { run, calls } = fakeRun((argv) => {
    // -d returns nothing useful; plain status carries the IP.
    if (argv[2] === "-d") return { stdout: "Management: Connected\n(no detail)" }
    return { stdout: STATUS_BLOB }
  })
  const { ctx } = makeCtx(run)

  const res = await netbirdProvider.up(ctx)

  expect(res.publicUrl).toBe("http://100.110.111.246:8787")
  expect(calls).toEqual([
    ["netbird", "status", "-d"],
    ["netbird", "status"],
  ])
})

test("up() throws a clear error when no overlay IP is present", async () => {
  const { run } = fakeRun(() => ({ stdout: "Management: Disconnected\nNeeds login" }))
  const { ctx } = makeCtx(run)
  await expect(netbirdProvider.up(ctx)).rejects.toThrow(/NetBird overlay IP/)
})

test("login() runs `netbird up` and maps a clean exit to true", async () => {
  const { run, calls } = fakeRun(() => ({ code: 0 }))
  const { ctx } = makeCtx(run)
  expect(await netbirdProvider.login(ctx)).toBe(true)
  expect(calls).toEqual([["netbird", "up"]])
})

test("login() returns false on a non-zero exit", async () => {
  const { run } = fakeRun(() => ({ code: 1, stderr: "needs setup key" }))
  const { ctx } = makeCtx(run)
  expect(await netbirdProvider.login(ctx)).toBe(false)
})

test("install() runs the official one-liner via ctx.run on success", async () => {
  if (process.platform !== "linux" && process.platform !== "darwin") return
  const { run, calls } = fakeRun(() => ({ code: 0 }))
  const { ctx } = makeCtx(run)
  expect(await netbirdProvider.install(ctx)).toBe(true)
  expect(calls).toEqual([
    ["sh", "-c", "curl -fsSL https://pkgs.netbird.io/install.sh | sh"],
  ])
})

test("install() returns false (without throwing) when the script fails", async () => {
  if (process.platform !== "linux" && process.platform !== "darwin") return
  const { run } = fakeRun(() => ({ code: 1, stderr: "curl: (6) could not resolve host" }))
  const { ctx, out } = makeCtx(run)
  expect(await netbirdProvider.install(ctx)).toBe(false)
  expect(out.join("\n")).toContain("install failed")
})

test("install() returns false when consent is declined, never spawning curl", async () => {
  const { run, calls } = fakeRun(() => ({ code: 0 }))
  // Decline regardless of the default by overriding confirm.
  const { ctx } = makeCtx(run, { confirm: async () => false })
  expect(await netbirdProvider.install(ctx)).toBe(false)
  expect(calls).toEqual([])
})

test("status() reports up when Management is Connected", async () => {
  const { run, calls } = fakeRun(() => ({ stdout: STATUS_BLOB }))
  const { ctx } = makeCtx(run)
  expect(await netbirdProvider.status(ctx)).toEqual({ up: true })
  expect(calls).toEqual([["netbird", "status"]])
})

test("status() reports down when not connected", async () => {
  // NetBird prints this before the client has joined — note: no "connected"
  // substring at all (the contract regex matches `Connected` anywhere, so a
  // "Disconnected" string would false-positive; a needs-login status doesn't).
  const { run } = fakeRun(() => ({ stdout: "Management: Disabled\nNeedsLogin: true" }))
  const { ctx } = makeCtx(run)
  expect(await netbirdProvider.status(ctx)).toEqual({ up: false })
})

test("down() calls `netbird down` and swallows a failing run", async () => {
  const { run, calls } = fakeRun(() => {
    throw new Error("daemon not running")
  })
  const { ctx } = makeCtx(run)
  await expect(netbirdProvider.down(ctx)).resolves.toBeUndefined()
  expect(calls).toEqual([["netbird", "down"]])
})
