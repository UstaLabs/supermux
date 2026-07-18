import type { SessionBackend } from "./session-backend"
import { createTmuxSessionBackend } from "./tmux-backend"
import { SessiondBackend } from "../sessiond/client"
import { sessiondEndpoint } from "../sessiond/secret"
import { STATE_DIR } from "../../shared/paths"

let testBackend: SessionBackend | undefined
let platformBackend: SessionBackend | undefined

export function createPlatformSessionBackend(
  platform: NodeJS.Platform,
  createPosixBackend: () => SessionBackend = createTmuxSessionBackend,
  createWindowsBackend: () => SessionBackend = () => new SessiondBackend({
    stateDir: STATE_DIR,
    endpoint: sessiondEndpoint(STATE_DIR, "win32"),
    platform: "win32",
  }),
): SessionBackend {
  return platform === "win32" ? createWindowsBackend() : createPosixBackend()
}

export function getSessionBackend(): SessionBackend {
  if (testBackend) return testBackend
  platformBackend ??= createPlatformSessionBackend(process.platform)
  return platformBackend
}

export function setSessionBackendForTests(backend?: SessionBackend): void {
  testBackend = backend
}
