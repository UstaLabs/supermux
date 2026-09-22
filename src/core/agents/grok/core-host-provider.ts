import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { createGrokCoreHost, type GrokCoreHost } from "./core-host"

export type GrokCoreHostFactory = () => GrokCoreHost

const provider = createHostProvider({
  create: () => createGrokCoreHost({
    stateDirectory: join(STATE_DIR, "core", "grok"),
  }),
})

/** Lazy process-owned Grok core host. Does not acquire the core lock at import time. */
export function getGrokCoreHost(): GrokCoreHost {
  return provider.get()
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeGrokCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
}

export function grokCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setGrokCoreHostFactoryForTests(next: GrokCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetGrokCoreHostProviderForTests(): void {
  provider.resetForTests()
}
