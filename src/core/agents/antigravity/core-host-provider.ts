import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { brokerAccountsOptions } from "../../accounts/broker-accounts"
import { createAntigravityCoreHost, type AntigravityCoreHost } from "./core-host"

export type AntigravityCoreHostFactory = () => AntigravityCoreHost

const provider = createHostProvider({
  create: () => createAntigravityCoreHost({
    stateDirectory: join(STATE_DIR, "core", "antigravity"),
    accounts: brokerAccountsOptions(),
  }),
})

/** Lazy process-owned Antigravity core host. Does not acquire the core lock at import time. */
export function getAntigravityCoreHost(): AntigravityCoreHost {
  return provider.get()
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeAntigravityCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
}

export function antigravityCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setAntigravityCoreHostFactoryForTests(next: AntigravityCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetAntigravityCoreHostProviderForTests(): void {
  provider.resetForTests()
}
