import type { LspInstallSpec } from "./catalog"
import { runInstall } from "./install"

/** Run a catalog install command to completion (for REST settings UI). */
export function runInstallToCompletion(
  spec: LspInstallSpec,
  timeoutMs = 600_000,
): Promise<{ ok: boolean; lines: string[] }> {
  const lines: string[] = []
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      handle.cancel()
      lines.push("install timed out")
      resolve({ ok: false, lines })
    }, timeoutMs)
    const handle = runInstall(
      spec.cmd,
      (line) => lines.push(line),
      (ok) => {
        clearTimeout(timer)
        resolve({ ok, lines })
      },
      spec,
    )
  })
}
