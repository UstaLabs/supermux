import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import type { CloseMode } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { brokerAccountsOptions } from "../../accounts/broker-accounts"
import { createClaudeCoreHost, type ClaudeCoreHost } from "./core-host"

export type ClaudeCoreHostFactory = () => ClaudeCoreHost

const provider = createHostProvider({
  create: () => createClaudeCoreHost({
    stateDirectory: join(STATE_DIR, "core", "claude"),
    accounts: brokerAccountsOptions(),
  }),
})

export function getClaudeCoreHost(): ClaudeCoreHost {
  return provider.get()
}

/** [agents]: "detach" leaves the keepers running for the next broker (a restart); "shutdown" stops them (a stop). */
export async function closeClaudeCoreHost(agents: CloseMode = "shutdown"): Promise<void> {
  await provider.close({ agents })
}

export function claudeCoreHostIsShutdown(): boolean {
  return provider.isShutdown()
}

export function setClaudeCoreHostFactoryForTests(next: ClaudeCoreHostFactory | undefined): void {
  provider.setFactoryForTests(next)
}

export function resetClaudeCoreHostProviderForTests(): void {
  provider.resetForTests()
}
