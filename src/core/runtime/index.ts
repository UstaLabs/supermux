import type { SessionBackend } from "./session-backend"
import { createTmuxSessionBackend } from "./tmux-backend"

let testBackend: SessionBackend | undefined
let platformBackend: SessionBackend | undefined

export function createPlatformSessionBackend(
  platform: NodeJS.Platform,
  createPosixBackend: () => SessionBackend = createTmuxSessionBackend,
): SessionBackend {
  if (platform === "win32") throw new Error("Windows session backend is not initialized")
  return createPosixBackend()
}

export function getSessionBackend(): SessionBackend {
  if (testBackend) return testBackend
  platformBackend ??= createPlatformSessionBackend(process.platform)
  return platformBackend
}

export function setSessionBackendForTests(backend?: SessionBackend): void {
  testBackend = backend
}
