import { test, expect } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
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

/** What the fake background starts and stops saw. */
const started: string[][] = []
const stopped: number[] = []

/** Minimal non-interactive ctx (no TTY, --yes). Override per test. A background start gets pid 4242. */
function ctx(over: Partial<ConnectCtx> & { run: Run }): ConnectCtx {
  started.length = 0
  stopped.length = 0
  return {
    port: "8787",
    stateDir: mkdtempSync(join(tmpdir(), "mux-ngrok-")),
    tty: false,
    yes: true,
    println: () => {},
    ask: async () => null,
    confirm: async (_p, def) => def,
    startDetached: (argv) => { started.push(argv); return 4242 },
    stopTree: (pid) => { stopped.push(pid) },
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
  const c = ctx({ run, mode: "reserved", publicUrlHint: "mux.ngrok.app" })
  const res = await ngrokProvider.up(c)
  expect(res.publicUrl).toBe("https://mux.ngrok.app")
  expect(res.stable).toBe(true)
  // The durable run binds the reserved domain, launched DETACHED so it survives
  // this CLI instead of hanging it; no shell (Windows has none).
  expect(started).toEqual([["ngrok", "http", "--domain=mux.ngrok.app", "8787"]])
  expect(readFileSync(join(c.stateDir, "ngrok.pid"), "utf8")).toBe("4242")
  expect(calls.some((argv) => argv[0] === "sh")).toBe(false)
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
  // Launched DETACHED, then the URL is read from the local API via curl.
  expect(started).toEqual([["ngrok", "http", "8787"]])
  expect(calls.map((c) => c.join(" "))).toContain("curl -s http://127.0.0.1:4040/api/tunnels")
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
  const c = ctx({ run })
  writeFileSync(join(c.stateDir, "ngrok.pid"), "4242")
  await expect(ngrokProvider.down(c)).resolves.toBeUndefined()
  expect(stopped).toEqual([4242])
  expect(existsSync(join(c.stateDir, "ngrok.pid"))).toBe(false)
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
