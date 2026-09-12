import { join } from "path"
import { STATE_DIR } from "../../../shared/paths"
import { createGrokCoreHost, type GrokCoreHost } from "./core-host"

export type GrokCoreHostFactory = () => GrokCoreHost

let factory: GrokCoreHostFactory | undefined
let processHost: GrokCoreHost | undefined
let shutdown = false

function defaultFactory(): GrokCoreHost {
  return createGrokCoreHost({
    stateDirectory: join(STATE_DIR, "core", "grok"),
  })
}

/** Lazy process-owned Grok core host. Does not acquire the core lock at import time. */
export function getGrokCoreHost(): GrokCoreHost {
  if (shutdown) {
    throw new Error("Grok core host is closed")
  }
  if (!processHost) {
    processHost = (factory ?? defaultFactory)()
  }
  return processHost
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeGrokCoreHost(): Promise<void> {
  shutdown = true
  const current = processHost
  if (!current) return
  await current.close()
  processHost = undefined
}

export function grokCoreHostIsShutdown(): boolean {
  return shutdown
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setGrokCoreHostFactoryForTests(next: GrokCoreHostFactory | undefined): void {
  if (processHost) {
    throw new Error("cannot replace a live Grok core host")
  }
  factory = next
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetGrokCoreHostProviderForTests(): void {
  shutdown = false
  processHost = undefined
  factory = undefined
}
