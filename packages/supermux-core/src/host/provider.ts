import type { CloseMode } from "../types.js"
import { requireAgentsCloseMode } from "../types.js"
import type { Host } from "./index.js"

export type HostFactory = () => Host

export type HostProvider = {
  get(): Host
  close(options: { agents: CloseMode }): Promise<void>
  isShutdown(): boolean
  setFactoryForTests(next: HostFactory | undefined): void
  resetForTests(): void
}

export function createHostProvider(options: { create: HostFactory }): HostProvider {
  if (typeof options.create !== "function") {
    throw new TypeError("create is required")
  }
  let factory: HostFactory | undefined
  let processHost: Host | undefined
  let shutdown = false
  const defaultCreate = options.create

  return {
    get() {
      if (shutdown) {
        throw new Error("core host is closed")
      }
      if (!processHost) {
        processHost = (factory ?? defaultCreate)()
      }
      return processHost
    },
    async close(options: { agents: CloseMode }) {
      const agents = requireAgentsCloseMode(options)
      shutdown = true
      const current = processHost
      if (!current) return
      await current.close({ agents })
      processHost = undefined
    },
    isShutdown() {
      return shutdown
    },
    setFactoryForTests(next) {
      if (processHost) {
        throw new Error("cannot replace a live core host")
      }
      factory = next
    },
    resetForTests() {
      shutdown = false
      processHost = undefined
      factory = undefined
    },
  }
}
