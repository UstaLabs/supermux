import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { createCodexCoreHost, type CodexCoreHost } from "./core-host"

export type CodexCoreHostFactory = () => CodexCoreHost

const provider = createHostProvider({
  create: () => createCodexCoreHost({
    stateDirectory: join(STATE_DIR, "core", "codex"),
  }),
})

/** Lazy process-owned Codex core host. Does not acquire the core lock at import time. */
export function getCodexCoreHost(): CodexCoreHost {
  return provider.get()
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeCodexCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
}

export function codexCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setCodexCoreHostFactoryForTests(next: CodexCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetCodexCoreHostProviderForTests(): void {
  provider.resetForTests()
}
