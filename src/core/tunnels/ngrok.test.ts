import { test, expect } from "bun:test"
import { ngrokProvider } from "./ngrok"
import type { ConnectCtx, Run, RunResult } from "./types"

/** A recording fake Run. `replies` maps a substring of argv.join(" ") → result. */
function fakeRun(replies: Array<[string, Partial<RunResult>]> = []): {
  run: Run
  calls: string[][]
} {
  const calls: string[][] = []
  const run: Run = async (argv) => {
    calls.push(argv)
    const line = argv.join(" ")
    for (const [needle, res] of replies) {
      if (line.includes(needle)) {
        return { code: 0, stdout: "", stderr: "", ...res }
      }
    }
    return { code: 0, stdout: "", stderr: "" }
  }
  return { run, calls }
}

/** Minimal non-interactive ctx (no TTY, --yes). Override per test. */
function ctx(over: Partial<ConnectCtx> & { run: Run }): ConnectCtx {
  return {
    port: "8787",
    stateDir: "/tmp/mux-test",
    tty: false,
    yes: true,
    println: () => {},
    ask: async () => null,
    confirm: async (_p, def) => def,
    ...over,
  }
}

test("identity/modes match the contract (reserved is the stable default)", () => {
  expect(ngrokProvider.id).toBe("ngrok")
  expect(ngrokProvider.bin).toBe("ngrok")
  expect(ngrokProvider.label).toBe("ngrok")
  expect(ngrokProvider.modes[0]).toEqual({
    id: "reserved",
    label: "Reserved domain (stable)",
    stable: true,
  })
  expect(ngrokProvider.modes[1]).toEqual({
    id: "random",
    label: "Random URL (throwaway)",
    stable: false,
  })
})

test("up(reserved): uses publicUrlHint host → https://<domain>, stable", async () => {
  const { run, calls } = fakeRun()
  const res = await ngrokProvider.up(
    ctx({ run, mode: "reserved", publicUrlHint: "mux.ngrok.app" }),
  )
  expect(res.publicUrl).toBe("https://mux.ngrok.app")
  expect(res.stable).toBe(true)
  // The durable run binds the reserved domain to the local port.
  const ran = calls.map((c) => c.join(" "))
  expect(ran).toContain("ngrok http --domain=mux.ngrok.app 8787")
})

test("up(reserved): with no hint, asks for the domain", async () => {
  const { run } = fakeRun()
  const res = await ngrokProvider.up(
    ctx({ run, mode: "reserved", ask: async () => "asked.ngrok.app" }),
  )
  expect(res.publicUrl).toBe("https://asked.ngrok.app")
  expect(res.stable).toBe(true)
})

test("up(random): reads the URL from the local 4040 API, stable:false + caveat", async () => {
  const { run, calls } = fakeRun([
    ["curl", { stdout: '{"tunnels":[{"public_url":"https://abc123.ngrok-free.app"}]}' }],
  ])
  const res = await ngrokProvider.up(ctx({ run, mode: "random" }))
  expect(res.publicUrl).toBe("https://abc123.ngrok-free.app")
  expect(res.stable).toBe(false)
  expect(res.notes?.[0]).toContain("Throwaway URL")
  expect(res.notes?.[0]).toContain("ONE reserved domain")
  // It must start the tunnel, then read the API via curl (no real network).
  const ran = calls.map((c) => c.join(" "))
  expect(ran).toContain("ngrok http 8787")
  expect(ran).toContain("curl -s http://127.0.0.1:4040/api/tunnels")
})

test("up(random): throws a clear error when the API yields no URL", async () => {
  const { run } = fakeRun([["curl", { stdout: '{"tunnels":[]}' }]])
  await expect(ngrokProvider.up(ctx({ run, mode: "random" }))).rejects.toThrow(
    /could not read the ngrok tunnel URL from the local API/,
  )
})

test("login: passes a pasted token to `ngrok config add-authtoken`", async () => {
  const { run, calls } = fakeRun()
  const ok = await ngrokProvider.login(ctx({ run, ask: async () => "tok_abc" }))
  expect(ok).toBe(true)
  expect(calls).toContainEqual(["ngrok", "config", "add-authtoken", "tok_abc"])
})

test("login: returns false (with guidance) on an empty/null token, no spawn", async () => {
  const { run, calls } = fakeRun()
  const out: string[] = []
  const ok = await ngrokProvider.login(
    ctx({ run, ask: async () => null, println: (s) => out.push(s) }),
  )
  expect(ok).toBe(false)
  expect(calls.length).toBe(0)
  expect(out.join("\n")).toContain("authtoken")
})

test("status: up when curl succeeds and the API mentions public_url", async () => {
  const { run } = fakeRun([
    ["curl", { stdout: '{"tunnels":[{"public_url":"https://x.ngrok-free.app"}]}' }],
  ])
  const st = await ngrokProvider.status(ctx({ run }))
  expect(st.up).toBe(true)
  expect(st.url).toBe("https://x.ngrok-free.app")
})

test("status: down when curl fails (API not listening)", async () => {
  const { run } = fakeRun([["curl", { code: 7, stdout: "" }]])
  const st = await ngrokProvider.status(ctx({ run }))
  expect(st.up).toBe(false)
  expect(st.url).toBeUndefined()
})

test("down: best-effort `ngrok service uninstall`, swallows errors", async () => {
  const { run, calls } = fakeRun([
    ["service uninstall", { code: 1, stderr: "not installed" }],
  ])
  await expect(ngrokProvider.down(ctx({ run }))).resolves.toBeUndefined()
  expect(calls).toContainEqual(["ngrok", "service", "uninstall"])
})

test("install: on linux, declines (no brew path) → false + prints download URL", async () => {
  if (process.platform === "darwin") return
  const { run } = fakeRun()
  const out: string[] = []
  // yes:false + a TTY-less confirm that declines would still hit the download
  // branch on linux even when consent is granted (no safe auto-install).
  const ok = await ngrokProvider.install(
    ctx({ run, yes: true, println: (s) => out.push(s) }),
  )
  expect(ok).toBe(false)
  expect(out.join("\n")).toContain("https://ngrok.com/download")
})
