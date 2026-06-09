import { spawn as defaultSpawn, ChildProcess } from "child_process"
import { mkdirSync, writeFileSync } from "fs"
import { resolve } from "path"
import { createOpencodeClient } from "@opencode-ai/sdk"
import type { OpenCodeClientLike, OpenCodeCommandEntry } from "./adapter"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/opencode/spawn")

type RealClient = ReturnType<typeof createOpencodeClient>

export type OpenCodeSpawnHandle = {
  pid: number
  baseUrl: string
  client: OpenCodeClientLike
  child: ChildProcess
  kill: () => void
  onExit: (cb: (code: number | null) => void) => void
}

// Narrow seam for tests (Node's `spawn` is heavily overloaded; the fake only
// implements this call shape).
export type SpawnFn = (
  cmd: string,
  args: string[],
  opts: { cwd: string; env: Record<string, string>; stdio: ("pipe" | "ignore" | "inherit")[] },
) => ChildProcess

/** Wrap the real, heavily-generic SDK client down to the minimal slice the
 * adapter consumes. ALL SDK-version coupling lives in this one function. */
export function wrapOpenCodeClient(real: RealClient): OpenCodeClientLike {
  return {
    session: {
      create: (o) => real.session.create(o as never) as never,
      update: (o) => real.session.update(o as never) as never,
      prompt: (o) => real.session.prompt(o as never) as never,
      abort: (o) => real.session.abort(o as never) as never,
    },
    event: {
      subscribe: async () => {
        const res = (await real.event.subscribe()) as { stream: AsyncIterable<never> }
        return { stream: res.stream }
      },
    },
    listCommands: async (workdir: string): Promise<OpenCodeCommandEntry[]> => {
      const res = (await real.command.list({ query: { directory: workdir } })) as {
        data?: Array<{ name: string; description?: string; source?: string }>
      }
      return (res.data ?? []).map((c) => ({
        name: c.name,
        description: c.description,
        source: c.source,
      }))
    },
  }
}

async function freePort(): Promise<number> {
  const net = await import("net")
  return new Promise<number>((resolve, reject) => {
    const srv = net.createServer()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address()
      const port = addr && typeof addr === "object" ? addr.port : 0
      srv.close(() => (port ? resolve(port) : reject(new Error("could not allocate a free port"))))
    })
  })
}

async function waitForReady(real: RealClient, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let lastErr: unknown
  while (Date.now() < deadline) {
    try {
      // Poll session.list (needs no network) rather than config.providers, which
      // loads the models.dev catalog and can hang when offline. Race a short
      // per-attempt timeout so a hung call can never block the overall deadline.
      await Promise.race([
        real.session.list(),
        new Promise((_, reject) => setTimeout(() => reject(new Error("poll timeout")), 3000)),
      ])
      return
    } catch (err) {
      lastErr = err
      await new Promise((r) => setTimeout(r, 150))
    }
  }
  throw new Error(`opencode server not ready within ${timeoutMs}ms: ${String(lastErr)}`)
}

/** Spawn a per-session `opencode serve` child rooted at the session's workdir
 * (the server binds its directory to its cwd), then connect an SDK client to it.
 * Mirrors codex's spawnCodexAppServer: returns a handle with pid + onExit so
 * main.ts supervises the process the same way. */
export async function spawnOpenCodeServer(opts: {
  workdir: string
  /** session-private XDG_CONFIG_HOME (holds the mux-shim MCP config) */
  configHome: string
  authEnv: Record<string, string>
  port?: number
  spawn?: SpawnFn
  createClient?: (baseUrl: string) => RealClient
  readyTimeoutMs?: number
  /** skip the readiness poll (tests) */
  skipReady?: boolean
}): Promise<OpenCodeSpawnHandle> {
  const spawnFn: SpawnFn = opts.spawn ?? (defaultSpawn as unknown as SpawnFn)
  const port = opts.port ?? (await freePort())
  const baseUrl = `http://127.0.0.1:${port}`
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    ...opts.authEnv,
    // session-private config (mux-shim MCP + instructions); auth (XDG_DATA_HOME)
    // is intentionally left at the user's value.
    XDG_CONFIG_HOME: opts.configHome,
  }
  // Pre-seed session-private opencode config with permission auto-allow so
  // subagents spawned via `task` don't hang on unanswerable permission asks.
  const configDir = resolve(opts.configHome, "opencode")
  mkdirSync(configDir, { recursive: true })
  writeFileSync(
    resolve(configDir, "opencode.jsonc"),
    JSON.stringify({
      $schema: "https://opencode.ai/config.json",
      permission: "allow",
    }, null, 2),
    "utf8",
  )
  const child = spawnFn("opencode", ["serve", "--hostname", "127.0.0.1", "--port", String(port)], {
    cwd: opts.workdir,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  })
  // Drain stdio so a chatty server can't fill the OS pipe buffer and stall.
  child.stdout?.on("data", () => {})
  child.stderr?.on("data", () => {})
  child.on("error", (err) => log.warn("opencode_serve_spawn_error", { err: err.message }))

  const makeClient = opts.createClient ?? ((u: string) => createOpencodeClient({ baseUrl: u, directory: opts.workdir } as never))
  const real = makeClient(baseUrl)
  // opencode's server cold-boot is ~20-25s (model catalog + provider init), so
  // the readiness budget must comfortably exceed that or every spawn times out.
  if (!opts.skipReady) await waitForReady(real, opts.readyTimeoutMs ?? 45_000)

  return {
    pid: child.pid ?? -1,
    baseUrl,
    client: wrapOpenCodeClient(real),
    child,
    kill: () => { try { child.kill("SIGTERM") } catch {} },
    onExit: (cb) => { child.on("exit", cb) },
  }
}
