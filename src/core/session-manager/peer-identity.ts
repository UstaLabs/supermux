// #156: which process is on the other end of a session socket, and is it part of that session?
//
// The socket's path names the session, but every process of the same OS user can open any session's socket. So the
// broker asks the KERNEL who connected (never the client), then walks that process's parents up to the session's
// keeper (the process that owns the agent). A token in the environment would not help: any process of the same user can
// read another's environment (/proc/<pid>/environ). This gives correct attribution and stops one session acting as
// another; the OS user stays the security boundary (a different uid is refused outright).
//
// Proven per OS in the #156 spikes (/p/cli-156/):
//   Linux  getsockopt(SO_PEERCRED) → pid + uid; parents from /proc/<pid>/stat. The keeper is a child subreaper
//          (prctl PR_SET_CHILD_SUBREAPER), so a process the agent detaches still descends from it.
//   macOS  getsockopt(SOL_LOCAL, LOCAL_PEERPID) + getpeereid; parents from proc_pidinfo(PROC_PIDTBSDINFO). No subreaper:
//          a detached process is re-parented to launchd and refused.
//   Windows session sockets are AF_UNIX files since #156 (local-endpoint.ts): WSAIoctl(SIO_AF_UNIX_GETPEERPID); parents
//          from one Toolhelp process snapshot per check. A connection on the legacy named pipe (an agent started before
//          the move) has no handle in Bun, so its peer is unknown and it passes (logged).
import { readFileSync } from "node:fs"
import type { Socket } from "node:net"
import { host } from "@supermux/platform"

export interface Peer { pid: number; uid: number | null }
export type PeerCheck = { ok: true; peer: Peer | null; why?: string } | { ok: false; peer: Peer | null; why: string }

type Ffi = { WSAIoctl?: (s: bigint, code: number, inBuf: unknown, inLen: number, outBuf: unknown, outLen: number, got: unknown, ov: unknown, cr: unknown) => number; CreateToolhelp32Snapshot?: (flags: number, pid: number) => bigint | number; Process32FirstW?: (snap: bigint | number, entry: unknown) => number; Process32NextW?: (snap: bigint | number, entry: unknown) => number; CloseHandle?: (h: bigint | number) => number; getsockopt: (fd: number, level: number, name: number, buf: unknown, len: unknown) => number; getpeereid?: (fd: number, uid: unknown, gid: unknown) => number; proc_pidinfo?: (pid: number, flavor: number, arg: bigint | number, buf: unknown, size: number) => number }
const loaded = new Map<string, Ffi | null>()
let ptrOf: ((v: ArrayBufferView) => unknown) | undefined

async function load(platform: NodeJS.Platform): Promise<Ffi | null> {
  if (loaded.has(platform)) return loaded.get(platform)!
  let f: Ffi | null = null
  try {
    const { dlopen, FFIType, ptr } = await import("bun:ffi")
    ptrOf = ptr as (v: ArrayBufferView) => unknown
    // Only the host's own library can load; another platform (a test's override) has none.
    if (platform === "linux" && host.os() === "linux") {
      f = dlopen("libc.so.6", { getsockopt: { args: [FFIType.i32, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 } }).symbols as unknown as Ffi
    } else if (platform === "win32" && host.os() === "win32") {
      const ws2 = dlopen("ws2_32.dll", { WSAIoctl: { args: [FFIType.u64, FFIType.u32, FFIType.ptr, FFIType.u32, FFIType.ptr, FFIType.u32, FFIType.ptr, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 } })
      const k32 = dlopen("kernel32.dll", {
        CreateToolhelp32Snapshot: { args: [FFIType.u32, FFIType.u32], returns: FFIType.u64 },
        Process32FirstW: { args: [FFIType.u64, FFIType.ptr], returns: FFIType.i32 },
        Process32NextW: { args: [FFIType.u64, FFIType.ptr], returns: FFIType.i32 },
        CloseHandle: { args: [FFIType.u64], returns: FFIType.i32 },
      })
      f = { ...(ws2.symbols as object), ...(k32.symbols as object), getsockopt: () => -1 } as unknown as Ffi
    } else if (platform === "darwin" && host.os() === "darwin") {
      f = dlopen("libSystem.B.dylib", {
        getsockopt: { args: [FFIType.i32, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 },
        getpeereid: { args: [FFIType.i32, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 },
        proc_pidinfo: { args: [FFIType.i32, FFIType.i32, FFIType.u64, FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
      }).symbols as unknown as Ffi
    }
  } catch { f = null }
  loaded.set(platform, f)
  return f
}

/** The connected socket's descriptor (Bun's net.Socket keeps it on its handle). */
export function fdOf(socket: Socket): number | undefined {
  const fd = (socket as unknown as { _handle?: { fd?: unknown } })._handle?.fd
  return typeof fd === "number" && fd >= 0 ? fd : undefined
}

/** The process on the other end, as the kernel reports it; null when this OS (or this socket) can't say. */
export async function peerOf(socket: Socket, platform: NodeJS.Platform = host.os()): Promise<Peer | null> {
  const fd = fdOf(socket)
  const f = await load(platform)
  if (fd === undefined || !f || !ptrOf) return null
  if (platform === "linux") {
    const cred = new Int32Array(3), len = new Uint32Array([12])
    return f.getsockopt(fd, 1 /* SOL_SOCKET */, 17 /* SO_PEERCRED */, ptrOf(cred), ptrOf(len)) === 0 && cred[0]! > 0 ? { pid: cred[0]!, uid: cred[1]! } : null
  }
  if (platform === "win32") {
    // SIO_AF_UNIX_GETPEERPID: an AF_UNIX socket's peer process id (Windows 10 1803+). No uid on Windows: the socket file
    // lives in the user's profile, which only that user can reach.
    if (!f.WSAIoctl) return null
    const pid = new Uint32Array(1), got = new Uint32Array(1)
    return f.WSAIoctl(BigInt(fd), 0x58000100, null, 0, ptrOf(pid), 4, ptrOf(got), null, null) === 0 && pid[0]! > 0 ? { pid: pid[0]!, uid: null } : null
  }
  if (platform === "darwin") {
    const pid = new Int32Array(1), len = new Uint32Array([4])
    if (f.getsockopt(fd, 0 /* SOL_LOCAL */, 0x002 /* LOCAL_PEERPID */, ptrOf(pid), ptrOf(len)) !== 0 || pid[0]! <= 0) return null
    const uid = new Uint32Array(1), gid = new Uint32Array(1)
    return { pid: pid[0]!, uid: f.getpeereid?.(fd, ptrOf(uid), ptrOf(gid)) === 0 ? uid[0]! : null }
  }
  return null
}

/** A process's parent and start time (start time guards against a reused pid); null when it's gone or unreadable. */
export async function parentOf(pid: number, platform: NodeJS.Platform = host.os()): Promise<{ ppid: number; start: string } | null> {
  if (platform === "linux") {
    try {
      // /proc/<pid>/stat: "pid (comm) state ppid … starttime(22nd)"; comm may hold spaces and parens, so cut after the last ")".
      const stat = readFileSync(`/proc/${pid}/stat`, "utf8")
      const f = stat.slice(stat.lastIndexOf(")") + 2).split(" ")
      return { ppid: Number(f[1]), start: f[19]! }
    } catch { return null }
  }
  if (platform === "darwin") {
    const f = await load(platform)
    if (!f?.proc_pidinfo || !ptrOf) return null
    const buf = new Uint8Array(136)  // struct proc_bsdinfo
    if (f.proc_pidinfo(pid, 3 /* PROC_PIDTBSDINFO */, 0, ptrOf(buf), 136) !== 136) return null
    const dv = new DataView(buf.buffer)
    return { ppid: dv.getUint32(16, true), start: String(dv.getBigUint64(120, true)) }
  }
  return null
}

/** Windows: every process's parent at one moment (a Toolhelp snapshot; PROCESSENTRY32W: pid at 8, parent at 32, 568 bytes). */
async function windowsParents(): Promise<Map<number, number>> {
  const m = new Map<number, number>()
  const f = await load("win32")
  if (!f?.CreateToolhelp32Snapshot || !ptrOf) return m
  const snap = f.CreateToolhelp32Snapshot(2 /* TH32CS_SNAPPROCESS */, 0)
  const entry = new Uint8Array(568), dv = new DataView(entry.buffer)
  dv.setUint32(0, 568, true)
  if (f.Process32FirstW!(snap, ptrOf(entry))) do { m.set(dv.getUint32(8, true), dv.getUint32(32, true)) } while (f.Process32NextW!(snap, ptrOf(entry)))
  f.CloseHandle!(snap)
  return m
}

/** True when `pid` is `root` or one of its descendants (walks parents; at most 64 steps). */
export async function descendsFrom(pid: number, root: number, platform: NodeJS.Platform = host.os()): Promise<boolean> {
  const win = platform === "win32" ? await windowsParents() : undefined
  let p = pid
  for (let i = 0; i < 64; i++) {
    if (p === root) return true
    if (p <= (win ? 0 : 1)) return false
    const ppid = win ? win.get(p) : (await parentOf(p, platform))?.ppid
    // Windows keeps a dead parent's pid on its children: a pid with no live process ends the walk.
    if (ppid === undefined || ppid === p) return false
    p = ppid
  }
  return false
}

/**
 * The check for one connection to session `sessionId`'s socket. `root`: the pid that owns the session's processes (its
 * keeper; a persistent-runtime Claude session's own pid), or undefined when it is unknown (then it passes, with why).
 */
export async function checkPeer(socket: Socket, root: number | undefined, opts: { platform?: NodeJS.Platform; uid?: number } = {}): Promise<PeerCheck> {
  const platform = opts.platform ?? host.os()
  const peer = await peerOf(socket, platform)
  if (!peer) return { ok: true, peer: null, why: `peer unknown on ${platform}` }
  const uid = opts.uid ?? process.getuid?.()
  if (peer.uid !== null && uid !== undefined && peer.uid !== uid) return { ok: false, peer, why: `process ${peer.pid} belongs to another OS user (uid ${peer.uid})` }
  if (root === undefined) return { ok: true, peer, why: "session root unknown" }
  return (await descendsFrom(peer.pid, root, platform)) ? { ok: true, peer } : { ok: false, peer, why: `process ${peer.pid} is not part of this session (not under its process ${root})` }
}
