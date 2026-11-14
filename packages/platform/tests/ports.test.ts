// #122 phase 4: which process listens on a port, and which session it belongs to (a dev server an agent started):
// a Browser tab's comments on that port's preview go to that session by default.
import { expect, test } from "bun:test"
import { ports } from "@supermux/platform"

test("Linux: the listener's inode from /proc/net/tcp, its pid from the fds that hold that socket", () => {
  const files: Record<string, string> = {
    "/proc/net/tcp":
      "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
      "   0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 424242 1 0000000000000000 100 0 0 10 0\n" +
      "   1: 0100007F:1435 0100007F:C350 01 00000000:00000000 00:00000000 00000000  1000        0 515151 1 0000000000000000 100 0 0 10 0\n",
    "/proc/net/tcp6": "  sl  local_address remote_address st\n",
    "/proc/3100/stat": "3100 (node) S 3000 3100 3100 0 -1 4194304",
    "/proc/3000/stat": "3000 (bash (login)) S 2000 3000 3000 0 -1 4194304",
  }
  const deps = {
    platform: "linux" as const,
    readFile: (p: string) => { if (p in files) return files[p]!; throw new Error("ENOENT") },
    readdir: (p: string) => p === "/proc" ? ["1", "3100", "self", "3000"] : p === "/proc/3100/fd" ? ["0", "1", "17"] : [],
    readlink: (p: string) => p === "/proc/3100/fd/17" ? "socket:[424242]" : "/dev/null",
  }
  expect(ports.listenerPid(5173, deps)).toBe(3100) // 0x1435 = 5173, state 0A = LISTEN (the established one is not it)
  expect(ports.listenerPid(5174, deps)).toBeNull()
  expect(ports.parentPid(3100, deps)).toBe(3000)
  expect(ports.parentPid(3000, deps)).toBe(2000) // a name with spaces and parentheses
})

test("macOS: lsof's listener, ps's parent", () => {
  const run = (cmd: string, args: string[]) =>
    cmd === "lsof" && args.join(" ") === "-nP -iTCP:5173 -sTCP:LISTEN -t" ? "812\n"
      : cmd === "ps" && args.join(" ") === "-o ppid= -p 812" ? "  700\n"
      : ""
  expect(ports.listenerPid(5173, { platform: "darwin", run })).toBe(812)
  expect(ports.parentPid(812, { platform: "darwin", run })).toBe(700)
  expect(ports.listenerPid(9999, { platform: "darwin", run })).toBeNull()
})

test("Windows: netstat's LISTENING line for the port (IPv4 or IPv6), and the parents from one process list", () => {
  const run = (cmd: string) =>
    cmd === "netstat" ? "\r\n  Proto  Local Address  Foreign Address  State  PID\r\n" +
      "  TCP    0.0.0.0:51730   0.0.0.0:0   LISTENING   11\r\n" +
      "  TCP    [::]:5173       [::]:0      LISTENING   4120\r\n" +
      "  TCP    127.0.0.1:5173  127.0.0.1:50000  ESTABLISHED  4120\r\n"
      : "ProcessId,ParentProcessId\r\n4120,4000\r\n4000,3900\r\n"
  expect(ports.listenerPid(5173, { platform: "win32", run })).toBe(4120)
  expect(ports.parentPid(4120, { platform: "win32", run })).toBe(4000)
})

test("the owner is the first of the given pids on the way up from the listener", () => {
  const parents = new Map([[50, 40], [40, 30], [30, 1]])
  const deps = { listener: () => 50, parent: (pid: number) => parents.get(pid) ?? null }
  expect(ports.ownerAmong(5173, new Set([30, 99]), deps)).toBe(30)
  expect(ports.ownerAmong(5173, new Set([50]), deps)).toBe(50) // the session's own process listens
  expect(ports.ownerAmong(5173, new Set([99]), deps)).toBeNull() // reparented to init, or someone else's
  expect(ports.ownerAmong(5173, new Set([30]), { listener: () => null, parent: () => null })).toBeNull()
})

test("on this machine: the test's own server is found listening (Linux and macOS)", () => {
  if (process.platform !== "linux") return
  const server = Bun.serve({ port: 0, fetch: () => new Response("ok") })
  try {
    expect(ports.listenerPid(server.port!)).toBe(process.pid)
  } finally {
    server.stop(true)
  }
})
