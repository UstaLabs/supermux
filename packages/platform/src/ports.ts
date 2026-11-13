// Ports: which process listens on a TCP port, and its parent (#122 phase 4: a dev server an agent started is that
// session's, so a Browser tab's comments on its preview go there). No runtime says who listens on a port; each OS has
// its own way: /proc on Linux, lsof and ps on macOS, netstat and the process list on Windows.
import { spawnSync } from "node:child_process"
import { readdirSync, readFileSync, readlinkSync } from "node:fs"

export interface PortDeps {
  /** The OS whose way applies; the running one by default. */
  platform?: NodeJS.Platform
  readFile?: (path: string) => string
  readdir?: (path: string) => string[]
  readlink?: (path: string) => string
  /** Runs a command and answers its stdout ("" when it fails or isn't there). */
  run?: (command: string, args: string[]) => string
}

function runSync(command: string, args: string[]): string {
  const r = spawnSync(command, args, { encoding: "utf8", timeout: 5_000, windowsHide: true })
  return r.status === 0 ? r.stdout ?? "" : ""
}

/** The pid of the process listening on TCP [port] on this machine, or null. */
export function listenerPid(port: number, deps: PortDeps = {}): number | null {
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return null
  const platform = deps.platform ?? process.platform
  const run = deps.run ?? runSync
  if (platform === "linux") return linuxListener(port, deps)
  if (platform === "win32") {
    for (const line of run("netstat", ["-ano", "-p", "TCP"]).concat(run("netstat", ["-ano", "-p", "TCPv6"])).split(/\r?\n/)) {
      const m = line.trim().match(/^TCP\s+(\S+):(\d+)\s+\S+\s+LISTENING\s+(\d+)$/i)
      if (m && Number(m[2]) === port) return Number(m[3])
    }
    return null
  }
  const pid = Number(run("lsof", ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-t"]).split(/\s+/).find(Boolean))
  return Number.isInteger(pid) && pid > 0 ? pid : null
}

/** The parent of process [pid], or null. */
export function parentPid(pid: number, deps: PortDeps = {}): number | null {
  const platform = deps.platform ?? process.platform
  const run = deps.run ?? runSync
  let parent: number
  if (platform === "linux") {
    const stat = tryRead(deps, `/proc/${pid}/stat`)
    // pid (comm) state ppid …: comm may hold spaces and parentheses, so read after the last ")".
    parent = stat ? Number(stat.slice(stat.lastIndexOf(")") + 1).trim().split(/\s+/)[1]) : NaN
  } else if (platform === "win32") {
    const list = run("powershell", ["-NoProfile", "-Command", "Get-CimInstance Win32_Process | ForEach-Object { \"$($_.ProcessId),$($_.ParentProcessId)\" }"])
    const row = list.split(/\r?\n/).map((l) => l.trim().split(",")).find((r) => Number(r[0]) === pid)
    parent = row ? Number(row[1]) : NaN
  } else {
    parent = Number(run("ps", ["-o", "ppid=", "-p", String(pid)]).trim())
  }
  return Number.isInteger(parent) && parent > 0 ? parent : null
}

/**
 * The first of [pids] met on the way up from [port]'s listener (itself, its parent, …), or null: whose process
 * started the server. A server that left its starter (reparented to init) belongs to none.
 */
export function ownerAmong(
  port: number,
  pids: ReadonlySet<number>,
  deps: { listener?: (port: number) => number | null; parent?: (pid: number) => number | null } = {},
): number | null {
  const parent = deps.parent ?? ((p: number) => parentPid(p))
  let pid = (deps.listener ?? ((p: number) => listenerPid(p)))(port)
  for (let hops = 0; pid !== null && pid > 1 && hops < 64; hops++) {
    if (pids.has(pid)) return pid
    pid = parent(pid)
  }
  return null
}

function tryRead(deps: PortDeps, path: string): string | null {
  try {
    return (deps.readFile ?? ((p: string) => readFileSync(p, "utf8")))(path)
  } catch {
    return null
  }
}

function linuxListener(port: number, deps: PortDeps): number | null {
  const want = port.toString(16).toUpperCase().padStart(4, "0")
  const inodes = new Set<string>()
  for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
    for (const line of (tryRead(deps, table) ?? "").split("\n").slice(1)) {
      const f = line.trim().split(/\s+/)
      // local_address is HEXIP:PORT, st 0A is LISTEN, inode is the tenth field.
      if (f.length > 9 && f[1]?.split(":")[1] === want && f[3] === "0A" && f[9] !== "0") inodes.add(f[9]!)
    }
  }
  if (!inodes.size) return null
  const readdir = deps.readdir ?? ((p: string) => readdirSync(p))
  const readlink = deps.readlink ?? ((p: string) => readlinkSync(p))
  let procs: string[]
  try { procs = readdir("/proc").filter((d) => /^\d+$/.test(d)) } catch { return null }
  for (const pid of procs) {
    let fds: string[]
    try { fds = readdir(`/proc/${pid}/fd`) } catch { continue } // another user's: not ours to read
    for (const fd of fds) {
      let target: string
      try { target = readlink(`/proc/${pid}/fd/${fd}`) } catch { continue }
      const m = target.match(/^socket:\[(\d+)\]$/)
      if (m && inodes.has(m[1]!)) return Number(pid)
    }
  }
  return null
}
