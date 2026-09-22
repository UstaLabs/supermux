import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { createOpenCodeCoreHost, type OpenCodeCoreHost } from "./core-host"

export type OpenCodeCoreHostFactory = () => OpenCodeCoreHost

const provider = createHostProvider({
  create: () => createOpenCodeCoreHost({
    stateDirectory: join(STATE_DIR, "core", "opencode"),
  }),
})

/** Lazy process-owned OpenCode core host. Does not acquire the core lock at import time. */
export function getOpenCodeCoreHost(): OpenCodeCoreHost {
  return provider.get()
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeOpenCodeCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
}

export function openCodeCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setOpenCodeCoreHostFactoryForTests(next: OpenCodeCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetOpenCodeCoreHostProviderForTests(): void {
  provider.resetForTests()
}
