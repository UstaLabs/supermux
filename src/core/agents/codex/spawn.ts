import { spawn as defaultSpawn, ChildProcess } from "child_process"
import { JsonRpcClient } from "./jsonrpc"
import { resolveCommand, spawnCommand, type FileExists } from "../../process/launcher"

export type CodexSpawnHandle = {
  pid: number
  client: JsonRpcClient
  child: ChildProcess
  kill: () => void
  onExit: (cb: (code: number | null) => void) => void
}

// Narrow seam for tests. Node's `spawn` is an overloaded function; the test
// fake only implements the three-arg call shape, so we type the injectable
// parameter as that shape rather than the full overload set.
export type SpawnFn = (
  cmd: string,
  args: string[],
  opts: { env: Record<string, string>; stdio: ("pipe" | "ignore" | "inherit")[] },
) => ChildProcess

export function spawnCodexAppServer(opts: {
  codexHome: string
  workdir: string
  authEnv: Record<string, string>
  spawn?: SpawnFn
  model?: string
  reasoningLevel?: string
  /** supermux plugin-host flags (`-c plugins."<name>@mux".enabled=true` pairs). */
  pluginConfigArgs?: string[]
  platform?: NodeJS.Platform
  fileExists?: FileExists
}): CodexSpawnHandle {
  const spawnFn: SpawnFn = opts.spawn ?? (defaultSpawn as unknown as SpawnFn)
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    ...opts.authEnv,
    CODEX_HOME: opts.codexHome,
  }
  // codex app-server (0.133) does NOT accept --dangerously-bypass-* or --cd;
  // those flags caused the child to exit immediately with "unexpected
  // argument" — and adapter.start() then hung forever writing JSON-RPC to a
  // dead pipe. The cwd is passed via thread/start; sandbox + approval bypass
  // is configured via -c overrides.
  const args = [
    "app-server",
    "-c", 'approval_policy="never"',
    "-c", 'sandbox_mode="danger-full-access"',
    ...(opts.model ? ["-c", `model="${opts.model}"`] : []),
    ...(opts.reasoningLevel ? ["-c", `model_reasoning_effort="${opts.reasoningLevel}"`] : []),
    ...(opts.pluginConfigArgs ?? []),
  ]
  const platform = opts.platform ?? process.platform
  const shouldResolve = !opts.spawn || opts.platform !== undefined || opts.fileExists !== undefined
  const command = shouldResolve ? (resolveCommand(["codex"], env, platform, { fileExists: opts.fileExists }) ?? "codex") : "codex"
  const child = spawnCommand(command, args, {
    platform, fileExists: opts.fileExists, spawn: spawnFn as never, env, stdio: ["pipe", "pipe", "pipe"],
  })
  const client = new JsonRpcClient({ stdin: child.stdin!, stdout: child.stdout! })

  // Consume stderr — same pipe-buffer deadlock pattern as the cursor runner.
  // Without this, a chatty codex stderr could fill the OS pipe buffer and
  // stall the process.
  child.stderr?.on("data", () => {})

  // Wire fail-fast Promise rejection on child death. If codex exits or
  // errors before JSON-RPC requests resolve, the client surfaces a clean
  // error instead of hanging forever.
  child.on("exit", (code, signal) => {
    client.dispose(new Error(`codex app-server exited (code=${code}, signal=${signal})`))
  })
  child.on("error", (err) => {
    client.dispose(new Error(`codex app-server failed to spawn: ${err.message}`))
  })

  return {
    pid: child.pid ?? -1,
    client,
    child,
    kill: () => { try { child.kill("SIGTERM") } catch {} },
    onExit: (cb) => { child.on("exit", cb) },
  }
}
