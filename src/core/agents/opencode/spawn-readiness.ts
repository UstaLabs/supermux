import type { ChildProcess } from "child_process"

/**
 * Resolve when the server's readiness probe (`ready`) succeeds — but reject
 * IMMEDIATELY if the child process dies first. A missing or broken `opencode`
 * binary then fails fast with an accurate message instead of waiting out the
 * full readiness timeout (the 45s hang that surfaced as a tunnel 502 / a
 * misleading "opencode server not ready within 45000ms"). Mirrors codex's
 * spawn, which rejects on child `exit`/`error`.
 *
 * Listeners are removed once the race settles, so if `ready` wins, a later
 * child death can't reject a promise nobody awaits.
 */
export function awaitServerReady(child: ChildProcess, ready: Promise<void>): Promise<void> {
  let onError!: (err: Error & { code?: string }) => void
  let onExit!: (code: number | null, signal: NodeJS.Signals | null) => void
  const death = new Promise<never>((_resolve, reject) => {
    onError = (err) =>
      reject(
        new Error(
          `opencode failed to start: ${err.message}` +
            (err.code === "ENOENT" ? " (is `opencode` installed and on PATH?)" : ""),
        ),
      )
    onExit = (code, signal) =>
      reject(new Error(`opencode server exited before becoming ready (code=${code}, signal=${signal})`))
    child.once("error", onError)
    child.once("exit", onExit)
  })
  return Promise.race([ready, death]).finally(() => {
    child.removeListener("error", onError)
    child.removeListener("exit", onExit)
  }) as Promise<void>
}
