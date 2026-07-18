import { SessionStore } from "../../src/core/sessiond/session-store"

const encoder = new TextEncoder()
const KILL_TIMEOUT_MS = 12_000

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
  while (Date.now() < deadline) {
    const output = await store.capture(targetId, true)
    const match = output?.match(pattern)
    if (match) return match
    await Bun.sleep(50)
  }
  throw new Error(`timed out waiting for ConPTY output matching ${pattern}`)
}

function processExists(pid: number): boolean {
  const check = Bun.spawnSync([
    "powershell.exe",
    "-NoLogo",
    "-NoProfile",
    "-NonInteractive",
    "-Command",
    `if (Get-Process -Id ${pid} -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }`,
  ])
  return check.exitCode === 0
}

export function buildNestedChildCommand(): string {
  return [
    "$child = Start-Process -FilePath powershell.exe",
    "-ArgumentList @('-NoLogo','-NoProfile','-Command','Start-Sleep -Seconds 600')",
    "-PassThru",
  ].join(" ") + "; Write-Output ('SUPERMUX_CHILD_PID=' + $child.Id)\r"
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
    argv: ["powershell.exe", "-NoLogo", "-NoProfile"],
    env: { ...inheritedEnv, TERM: "xterm-256color" },
    cols: 80,
    rows: 24,
  })

  try {
    // Split the marker so ConPTY input echo cannot satisfy the output check.
    await store.write(target.id, encoder.encode("Write-Output ('SUPERMUX_' + 'CONPTY_OK')\r"))
    await store.resize(target.id, 120, 40)
    await waitForCapture(store, target.id, /SUPERMUX_CONPTY_OK/u, 10_000)

    await store.write(target.id, encoder.encode(buildNestedChildCommand()))
    const childMatch = await waitForCapture(store, target.id, /SUPERMUX_CHILD_PID=(\d+)/u, 10_000)
    const childPid = Number(childMatch[1])
    if (!Number.isSafeInteger(childPid) || childPid <= 0) throw new Error("ConPTY smoke returned an invalid child PID")
    if (!processExists(target.pid!) || !processExists(childPid)) {
      throw new Error("root or nested child exited before Job Object termination could be exercised")
    }

    await withTimeout(store.kill(target.id), KILL_TIMEOUT_MS, "Job Object cleanup")
    await waitForTreeExit([target.pid!, childPid], 10_000)
    console.log("SUPERMUX_CONPTY_SMOKE_OK")
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
