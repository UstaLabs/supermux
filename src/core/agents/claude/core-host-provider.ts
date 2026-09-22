import { join } from "path"
import { createHostProvider } from "../../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../../shared/paths"
import { createClaudeCoreHost, type ClaudeCoreHost } from "./core-host"

export type ClaudeCoreHostFactory = () => ClaudeCoreHost

const provider = createHostProvider({
  create: () => createClaudeCoreHost({
    stateDirectory: join(STATE_DIR, "core", "claude"),
  }),
})

export function getClaudeCoreHost(): ClaudeCoreHost {
  return provider.get()
}

export async function closeClaudeCoreHost(): Promise<void> {
  await provider.close({ agents: "shutdown" })
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
