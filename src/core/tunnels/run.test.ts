import { test, expect } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { delimiter, join } from "path"
import { extractFirstUrl, realRun, realStartDetached, realStopTree, which } from "./run"
import { readFileSync } from "fs"

/** A command named [name] in a new folder put at the front of PATH after start, as main.ts adds the agent bin folders. */
function onPathAfterStart(name: string): () => void {
  const dir = mkdtempSync(join(tmpdir(), "mux-late-bin-"))
  if (process.platform === "win32") writeFileSync(join(dir, `${name}.cmd`), "@echo off\r\n")
  else writeFileSync(join(dir, name), "#!/bin/sh\n", { mode: 0o755 })
  const before = process.env.PATH
  process.env.PATH = `${dir}${delimiter}${before ?? ""}`
  return () => {
    if (before === undefined) delete process.env.PATH
    else process.env.PATH = before
    rmSync(dir, { recursive: true, force: true })
  }
}

/** Run `fn` while capturing everything written to process.stdout (restored after). */
async function captureStdout(fn: () => Promise<void>): Promise<string> {
  const chunks: string[] = []
  const orig = process.stdout.write.bind(process.stdout)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(process.stdout as any).write = (chunk: any) => {
    chunks.push(typeof chunk === "string" ? chunk : new TextDecoder().decode(chunk))
    return true
  }
  try {
    await fn()
  } finally {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(process.stdout as any).write = orig
  }
  return chunks.join("")
}

test("realRun streams output live to process.stdout AND still captures it when stream:true", async () => {
  let res: Awaited<ReturnType<typeof realRun>> | undefined
  const live = await captureStdout(async () => {
    res = await realRun(["sh", "-c", "printf 'live-output-marker'"], { stream: true })
  })
  // Teed to the terminal as it happened — the user can see an auth URL mid-run.
  expect(live).toContain("live-output-marker")
  // …and still returned to the caller for the provider's own logic.
  expect(res?.stdout).toContain("live-output-marker")
  expect(res?.code).toBe(0)
})

test("realRun captures silently (no terminal echo) when stream is off", async () => {
  let res: Awaited<ReturnType<typeof realRun>> | undefined
  const live = await captureStdout(async () => {
    res = await realRun(["sh", "-c", "printf 'quiet-marker'"])
  })
  expect(live).not.toContain("quiet-marker")
  expect(res?.stdout).toContain("quiet-marker")
})

test("extractFirstUrl finds a trycloudflare URL amid noise", () => {
  const out =
    "2024 INF Request custom tunnel\n2024 INF |  https://random-words-here.trycloudflare.com  |\n2024 INF +--+"
  expect(extractFirstUrl(out, /trycloudflare\.com/)).toBe("https://random-words-here.trycloudflare.com")
})

test("extractFirstUrl returns the first url when no host filter", () => {
  expect(extractFirstUrl("see http://a.com and https://b.com")).toBe("http://a.com")
})

test("extractFirstUrl honors the host filter, skipping non-matching urls", () => {
  expect(extractFirstUrl("http://a.com https://x.ts.net", /ts\.net/)).toBe("https://x.ts.net")
})

test("extractFirstUrl trims trailing punctuation", () => {
  expect(extractFirstUrl("url: https://x.ts.net.")).toBe("https://x.ts.net")
})

test("extractFirstUrl returns undefined when there is no url", () => {
  expect(extractFirstUrl("no urls here")).toBeUndefined()
})

test("which returns false for a nonsense binary", () => {
  expect(which("definitely-not-a-real-bin-xyz-9000")).toBe(false)
})

// Bun.which without a PATH searches the PATH the process started with: a tunnel CLI in a folder main.ts added (under
// launchd's bare PATH, /opt/homebrew/bin, say) was reported missing.
test("which finds a binary in a folder added to PATH after start", () => {
  const restore = onPathAfterStart("mux-late-tunnel-cli")
  try {
    expect(which("mux-late-tunnel-cli")).toBe(true)
  } finally { restore() }
})

// Bun.spawn without `env` passes the environment the process started with: a variable set since (main.ts loads .env
// into process.env, and adds the agent bin folders to PATH) never reached the tunnel CLI.
test("realRun gives the child the environment as it is now", async () => {
  process.env.MUX_LATE_TUNNEL_VAR = "set-after-start"
  try {
    const r = await realRun([process.execPath, "-e", "console.log(process.env.MUX_LATE_TUNNEL_VAR ?? 'missing')"])
    expect(r.stdout.trim()).toBe("set-after-start")
  } finally {
    delete process.env.MUX_LATE_TUNNEL_VAR
  }
})

// The quick and random tunnels were started with `sh -c "nohup ... & echo $!"` and stopped with `kill $(cat pidfile)`:
// neither works on Windows, which has no sh. A tunnel CLI is started detached instead, its output going to a log.
test("realStartDetached starts a child that writes its output to the log; realStopTree ends it", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-detached-"))
  const log = join(dir, "tunnel.log")
  const pid = realStartDetached([process.execPath, "-e", "console.log('tunnel up'); setInterval(() => {}, 1000)"], log)
  try {
    expect(pid).toBeGreaterThan(0)
    let text = ""
    for (let i = 0; i < 50 && !text.includes("tunnel up"); i++) { await Bun.sleep(100); text = readFileSync(log, "utf8") }
    expect(text).toContain("tunnel up")
  } finally {
    realStopTree(pid)
  }
  let alive = true
  for (let i = 0; i < 50 && alive; i++) { await Bun.sleep(100); try { process.kill(pid, 0) } catch { alive = false } }
  expect(alive).toBe(false)
  rmSync(dir, { recursive: true, force: true })
})
