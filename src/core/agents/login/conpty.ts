import { spawnConptyLogin } from "../../../../packages/supermux-core/src/accounts/index.js"
import type { LoginProc } from "./session"
import type { LoginSpawnCommand } from "./spawn-command"

/**
 * A `conpty` login descriptor (Windows: claude, agy) as a LoginProc, through the same ConPTY
 * spawner the account login uses. A spawn failure is reported as output + exit 127 like a
 * missing binary; [cleanup] runs once, after the exit or the failure.
 */
export function conptyLoginProc(
  spec: LoginSpawnCommand,
  env: Record<string, string>,
  cleanup: () => void,
  onSpawnError: (message: string) => void,
  spawnPty: typeof spawnConptyLogin = spawnConptyLogin,
): LoginProc {
  try {
    const proc = spawnPty({ command: spec.cmd, args: spec.args, env, cwd: spec.cwd ?? process.cwd() })
    proc.onExit(cleanup)
    return { onStdout: proc.onOutput, onExit: proc.onExit, kill: proc.kill, write: proc.write }
  } catch (err: any) {
    cleanup()
    const message = err?.message ?? String(err)
    onSpawnError(message)
    let outCb: (chunk: string) => void = () => {}
    let exitCb: (code: number | null) => void = () => {}
    queueMicrotask(() => { outCb(`${spec.cmd} is not available: ${message}`); exitCb(127) })
    return { onStdout: cb => { outCb = cb }, onExit: cb => { exitCb = cb }, kill: () => {}, write: () => {} }
  }
}
