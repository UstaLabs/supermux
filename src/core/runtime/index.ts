import type { SessionBackend } from "./session-backend"
import { createTmuxSessionBackend } from "./tmux-backend"

let testBackend: SessionBackend | undefined
let platformBackend: SessionBackend | undefined

export function getSessionBackend(): SessionBackend {
  if (testBackend) return testBackend
  if (process.platform === "win32") throw new Error("Windows session backend is not initialized")
  platformBackend ??= createTmuxSessionBackend()
  return platformBackend
}

export function setSessionBackendForTests(backend?: SessionBackend): void {
  testBackend = backend
}
