import { dlopen, ptr } from "bun:ffi"

/**
 * agy keeps its Google login in the OS keyring (zalando/go-keyring: service "gemini", user
 * "antigravity") and writes ~/.gemini/antigravity-cli/antigravity-oauth-token only when the keyring
 * fails or times out. Windows' keyring is Credential Manager, which is always there: a signed-in agy
 * leaves no token file, only this generic credential (seen with agy 1.5.0 on Windows 11, 2026-11-04).
 */
export const ANTIGRAVITY_WINDOWS_CREDENTIAL = "gemini:antigravity"

/** @internal Whether a generic credential named `target` exists; injected in tests. */
export type CredentialExists = (target: string) => boolean

/**
 * True when agy's login is in the keyring. Only Windows' Credential Manager is asked; any error reads as
 * no. bun:ffi needs TinyCC, which Bun's x64 Windows build (the one we ship) has and its ARM64 build lacks.
 */
export function antigravityKeyringLogin(platform?: NodeJS.Platform, exists: CredentialExists = windowsCredentialExists): boolean {
  if ((platform ?? process.platform) !== "win32") return false // platform-ok: Windows Credential Manager probe (bun:ffi); moves into @supermux/platform's keyring gap (#29)
  try {
    return exists(ANTIGRAVITY_WINDOWS_CREDENTIAL)
  } catch {
    return false
  }
}

const CRED_TYPE_GENERIC = 1

let advapi32: ReturnType<typeof openAdvapi32> | undefined

function openAdvapi32() {
  return dlopen("advapi32.dll", {
    CredReadW: { args: ["ptr", "u32", "u32", "ptr"], returns: "i32" },
    CredFree: { args: ["u64"], returns: "void" },
  } as const)
}

/** CredReadW for a generic credential, freed at once: the secret is never read. Windows only. */
export function windowsCredentialExists(target: string): boolean {
  advapi32 ??= openAdvapi32()
  const name = Buffer.from(`${target}\0`, "utf16le")
  const credential = new BigUint64Array(1)
  if (advapi32.symbols.CredReadW(ptr(name), CRED_TYPE_GENERIC, 0, ptr(credential)) === 0) return false
  advapi32.symbols.CredFree(credential[0]!)
  return true
}
