// Processes. Only what the runtimes get wrong or leave out (platform-layer spec §3.3): Node and Bun signal a
// process the same way everywhere, but say nothing about who owns it.

/** Whether a process with [pid] exists. `EPERM` (it exists but is someone else's) is alive; only `ESRCH` is gone. */
export function isAlive(pid: number): boolean {
  return isAliveWith(pid, (p) => process.kill(p, 0))
}

/** [isAlive] with the signal-0 call injected, for tests. */
export function isAliveWith(pid: number, signal0: (pid: number) => unknown): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false
  try {
    signal0(pid)
    return true
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM"
  }
}
