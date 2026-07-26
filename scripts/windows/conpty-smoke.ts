import { SessionStore } from "../../src/core/sessiond/session-store"

const encoder = new TextEncoder()
const KILL_TIMEOUT_MS = 12_000

/**
 * Authoritative native-Windows ConPTY gate.
 *
 * Bun 1.3.14 requires NOT setting `detached: true` with `terminal:` (session-store).
 * On Windows 11 ARM64 with Bun x64-baseline, PowerShell argv + input + resize +
 * Job Object kill all pass under SessionStore after that fix.
 */
export function buildSmokeShellArgv(): string[] {
  return ["powershell.exe", "-NoLogo", "-NoProfile"]
}

export function buildOutputMarkerCommand(): string {
  // Compose at runtime so ConPTY input echo cannot satisfy the matcher alone.
  return "Write-Output ('SUPERMUX_CONPTY_' + 'OUTPUT_OK')\r"
}

export function buildNestedChildCommand(): string {
  return [
    "$child = Start-Process -FilePath powershell.exe",
    "-ArgumentList @('-NoLogo','-NoProfile','-Command','Start-Sleep -Seconds 600')",
    "-PassThru",
  ].join(" ") + "; Write-Output ('SUPERMUX_CHILD_PID=' + $child.Id)\r"
}

export function buildInputMarkerCommand(): string {
  return "Write-Output ('SUPERMUX_CONPTY_' + 'OK')\r"
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
    await waitForCapture(store, target.id, /powershell/iu, 30_000)

    await store.write(target.id, encoder.encode(buildOutputMarkerCommand()))
    await waitForCapture(store, target.id, /SUPERMUX_CONPTY_OUTPUT_OK/u, 15_000)

    await store.write(target.id, encoder.encode(buildNestedChildCommand()))
    const childMatch = await waitForCapture(store, target.id, /SUPERMUX_CHILD_PID=(\d+)/u, 15_000)
    const childPid = Number(childMatch[1])
    if (!Number.isSafeInteger(childPid) || childPid <= 0) throw new Error("invalid nested child PID")
    if (!processExists(target.pid!) || !processExists(childPid)) {
      throw new Error("root or nested child exited before Job Object termination could be exercised")
    }

    await store.resize(target.id, 120, 40)
    await withTimeout(store.kill(target.id), KILL_TIMEOUT_MS, "Job Object cleanup")
    await waitForTreeExit([target.pid!, childPid], 10_000)
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
      await waitForCapture(store, inputTarget.id, /powershell/iu, 30_000)
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
