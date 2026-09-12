import { join } from "path"
import { STATE_DIR } from "../../../shared/paths"
import { createCodexCoreHost, type CodexCoreHost } from "./core-host"

export type CodexCoreHostFactory = () => CodexCoreHost

let factory: CodexCoreHostFactory | undefined
let processHost: CodexCoreHost | undefined
let shutdown = false

function defaultFactory(): CodexCoreHost {
  return createCodexCoreHost({
    stateDirectory: join(STATE_DIR, "core", "codex"),
  })
}

/** Lazy process-owned Codex core host. Does not acquire the core lock at import time. */
export function getCodexCoreHost(): CodexCoreHost {
  if (shutdown) {
    throw new Error("Codex core host is closed")
  }
  if (!processHost) {
    processHost = (factory ?? defaultFactory)()
  }
  return processHost
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeCodexCoreHost(): Promise<void> {
  shutdown = true
  const current = processHost
  if (!current) return
  await current.close()
  processHost = undefined
}

export function codexCoreHostIsShutdown(): boolean {
  return shutdown
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setCodexCoreHostFactoryForTests(next: CodexCoreHostFactory | undefined): void {
  if (processHost) {
    throw new Error("cannot replace a live Codex core host")
  }
  factory = next
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetCodexCoreHostProviderForTests(): void {
  shutdown = false
  processHost = undefined
  factory = undefined
}
