import { credentials } from "@supermux/platform"

/**
 * agy keeps its Google login in the OS keyring (zalando/go-keyring: service "gemini", user
 * "antigravity") and writes ~/.gemini/antigravity-cli/antigravity-oauth-token only when the keyring
 * fails or times out. Windows' keyring is Credential Manager, which is always there: a signed-in agy
 * leaves no token file, only the generic credential "gemini:antigravity" (seen with agy 1.5.0 on Windows 11, 2026-11-04).
 */
const KEYRING_SERVICE = "gemini"
const KEYRING_USER = "antigravity"

/** @internal How Windows' store is read, by target name; injected in tests (the platform package's `windowsRead`). */
export type CredentialRead = (target: string) => boolean

/**
 * True when agy's login is in the OS keyring. EVERY error reads as no, on purpose: the answer feeds the agent's status
 * badge and the login's success check, and "can't tell" must never break detectAgent. That covers a store this OS has
 * no reader for yet (only Windows' Credential Manager is read), a runtime without Bun's ffi (Bun's Windows ARM64
 * build), and a Windows error other than "not found".
 */
export function antigravityKeyringLogin(platform?: NodeJS.Platform, windowsRead?: CredentialRead | null): boolean {
  try {
    return credentials.hasGenericCredential(KEYRING_SERVICE, KEYRING_USER, {
      ...(platform !== undefined ? { platform } : {}),
      ...(windowsRead !== undefined ? { windowsRead } : {}),
    })
  } catch {
    return false
  }
}
