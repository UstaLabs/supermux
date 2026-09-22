import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { createCursorCoreHost, type CursorCoreHost } from "./core-host"

export type CursorCoreHostFactory = () => CursorCoreHost

const provider = createHostProvider({
  create: () => createCursorCoreHost({
    stateDirectory: join(STATE_DIR, "core", "cursor"),
  }),
})

/** Lazy process-owned Cursor core host. Does not acquire the core lock at import time. */
export function getCursorCoreHost(): CursorCoreHost {
  return provider.get()
}

/** Await the current host (if any) and forbid creating another in this process.
 *  The live handle is kept until close confirms so a failed close can be retried. */
export async function closeCursorCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
}

export function cursorCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

/** Test seam: inject a factory before the process host exists. Refuses to replace a live owner. */
export function setCursorCoreHostFactoryForTests(next: CursorCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

/** Isolated-test reset. Production shutdown is one-way. */
export function resetCursorCoreHostProviderForTests(): void {
  provider.resetForTests()
}
