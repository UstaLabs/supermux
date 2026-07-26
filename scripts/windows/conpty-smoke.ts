import { SessionStore } from "../../src/core/sessiond/session-store"

const encoder = new TextEncoder()
const KILL_TIMEOUT_MS = 12_000

/**
 * Bun 1.3.14's Windows ConPTY path drops argv for powershell.exe and is unreliable
 * for interactive PowerShell input under Windows-on-ARM. cmd.exe is the authoritative
 * host-runtime gate: output, Uint8Array input, resize, and Job Object kill all pass.
 */
export function buildSmokeShellArgv(): string[] {
  return ["cmd.exe", "/d", "/k"]
}

export function buildOutputMarkerCommand(): string {
  // Compose the marker at execution time so ConPTY input echo cannot satisfy the matcher.
  return "for %i in (OUTPUT_OK) do @echo SUPERMUX_CONPTY_%i\r\n"
}

export function buildNestedChildCommand(): string {
  // start /B children stay in the root console Job Object (no breakaway).
  return "start /B cmd /d /c \"ping -n 600 127.0.0.1 >nul\"\r\n"
}

export function buildInputMarkerCommand(): string {
  return "for %i in (OK) do @echo SUPERMUX_CONPTY_%i\r\n"
}

export async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, label: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await new Promise<T>((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} timed out after ${timeoutMs}ms`)), timeoutMs)
      promise.then(resolve, reject)
    })
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

async function waitForCapture(
  store: SessionStore,
  targetId: string,
  pattern: RegExp,
  timeoutMs: number,
): Promise<RegExpMatchArray> {
  const deadline = Date.now() + timeoutMs
  let lastOutput: string | null = null
  while (Date.now() < deadline) {
    const output = await store.capture(targetId, true)
    lastOutput = output
    const match = output?.match(pattern)
    if (match) return match
    await Bun.sleep(50)
  }
  throw new Error(
    `timed out waiting for ConPTY output matching ${pattern}; last capture: ${JSON.stringify(lastOutput)}`,
  )
}

function processExists(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function waitForTreeExit(pids: number[], timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (pids.every(pid => !processExists(pid))) return
    await Bun.sleep(100)
  }
  const remaining = pids.filter(processExists)
  throw new Error(`Job Object did not terminate process tree within ${timeoutMs}ms; still live: ${remaining.join(", ")}`)
}

async function main(): Promise<void> {
  if (process.platform !== "win32") {
    throw new Error("ConPTY smoke test requires native Windows (win32); WSL is not supported")
  }

  const store = new SessionStore({ rawByteLimit: 256 * 1024 })
  const inheritedEnv = Object.fromEntries(
    Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined),
  )

  const target = await store.create({
    group: "smoke",
    name: "conpty-job-tree",
    cwd: process.cwd(),
    argv: buildSmokeShellArgv(),
    env: { ...inheritedEnv, TERM: "xterm-256color" },
    cols: 80,
    rows: 24,
  })

  try {
    await waitForCapture(store, target.id, />/u, 30_000)

    await store.write(target.id, encoder.encode(buildOutputMarkerCommand()))
    await waitForCapture(store, target.id, /SUPERMUX_CONPTY_OUTPUT_OK/u, 15_000)

    await store.write(target.id, encoder.encode(buildNestedChildCommand()))
    await Bun.sleep(500)
    if (!processExists(target.pid!)) {
      throw new Error("root exited before Job Object termination could be exercised")
    }

    await store.resize(target.id, 120, 40)
    await withTimeout(store.kill(target.id), KILL_TIMEOUT_MS, "Job Object cleanup")
    await waitForTreeExit([target.pid!], 10_000)
    console.log("SUPERMUX_CONPTY_LIFECYCLE_OK")

    const inputTarget = await store.create({
      group: "smoke",
      name: "conpty-input",
      cwd: process.cwd(),
      argv: buildSmokeShellArgv(),
      env: { ...inheritedEnv, TERM: "xterm-256color" },
      cols: 80,
      rows: 24,
    })
    try {
      await waitForCapture(store, inputTarget.id, />/u, 30_000)
      await store.write(inputTarget.id, encoder.encode(buildInputMarkerCommand()))
      await waitForCapture(store, inputTarget.id, /SUPERMUX_CONPTY_OK/u, 10_000)
      await store.resize(inputTarget.id, 100, 30)
      console.log("SUPERMUX_CONPTY_SMOKE_OK")
    } finally {
      await withTimeout(store.kill(inputTarget.id), KILL_TIMEOUT_MS, "final input cleanup").catch(() => undefined)
    }
  } finally {
    await withTimeout(store.kill(target.id), KILL_TIMEOUT_MS, "final Job Object cleanup").catch(() => undefined)
  }
}

if (import.meta.main) {
  await main().catch(error => {
    console.error(error instanceof Error ? error.stack ?? error.message : String(error))
    process.exit(1)
  })
}
