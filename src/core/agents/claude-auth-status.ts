import { spawnSync } from "node:child_process"

export type AuthStatusRunner = (command: string, args: string[]) => boolean

const runAuthStatus: AuthStatusRunner = (command, args) => {
  const result = spawnSync(command, args, {
    env: process.env,
    stdio: "ignore",
    timeout: 5_000,
  })
  return result.status === 0 && !result.error
}

/**
 * Claude stores browser-login credentials in the macOS Keychain. The broker's
 * launch-agent process can ask Claude to verify that credential, while checking
 * only ~/.claude/.credentials.json incorrectly reports a successful login as
 * unauthenticated.
 */
export function claudeCliIsAuthenticated(
  platform = process.platform,
  runner: AuthStatusRunner = runAuthStatus,
): boolean {
  if (platform !== "darwin") return false
  try {
    return runner("claude", ["auth", "status"])
  } catch {
    return false
  }
}
