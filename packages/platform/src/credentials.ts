// The OS credential store, read-only: whether it holds a generic credential, never what is in it. Windows' Credential
// Manager is read through Bun's ffi (advapi32 CredReadW), loaded only on win32 and only under Bun, so Node still loads
// this package. Where the store cannot be read, the answer is Unsupported or the OS's error, never a silent "no".
import { createRequire } from "node:module"
import { Unsupported } from "./errors.js"

export interface CredentialOptions {
  /** The OS whose store to ask; the running one by default (tests force the others). */
  platform?: NodeJS.Platform
  /** Reads Windows' store by target name; null where Bun's ffi is missing (Node, Bun's Windows ARM64 build). */
  windowsRead?: ((target: string) => boolean) | null
}

/**
 * Whether the OS credential store holds a generic credential for [service] and [account], under the name go-keyring
 * gives it (on Windows the target `service:account`). The secret is never read. Windows only for now: elsewhere, and
 * on a Windows runtime without Bun's ffi, it throws [Unsupported]; a Windows error other than "not found" throws too.
 */
export function hasGenericCredential(service: string, account: string, options: CredentialOptions = {}): boolean {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") throw new Unsupported("credentials.hasGenericCredential", `reading the credential store is not implemented on ${platform} yet`)
  const read = options.windowsRead === undefined ? windowsReader() : options.windowsRead
  if (!read) throw new Unsupported("credentials.hasGenericCredential", "reading Windows Credential Manager needs Bun's ffi, which this runtime does not have")
  return read(`${service}:${account}`)
}

const CRED_TYPE_GENERIC = 1
const ERROR_NOT_FOUND = 1168

/** @internal The Windows calls behind [hasGenericCredential], as a seam for tests off Windows. */
export interface CredentialApi {
  /** CredReadW for a generic credential, freed at once: true when it exists. */
  credRead(target: string): boolean
  /** The thread's last Windows error, read right after a failed [credRead]. */
  lastError(): number
}

/**
 * @internal [target]'s generic credential through [api]: false only for ERROR_NOT_FOUND. Any other failure throws
 * with its Windows error (an SSH key logon has no credential vault: ERROR_NO_SUCH_LOGON_SESSION, 1312), because
 * then this process could not look.
 */
export function readGenericCredential(api: CredentialApi, target: string): boolean {
  if (api.credRead(target)) return true
  const error = api.lastError()
  if (error === ERROR_NOT_FOUND) return false
  throw new Error(`CredReadW for "${target}" failed with Windows error ${error}`)
}

let reader: ((target: string) => boolean) | null | undefined

/** [readGenericCredential] over advapi32 and kernel32; null where there is no Bun ffi to call them with. */
function windowsReader(): ((target: string) => boolean) | null {
  if (reader !== undefined) return reader
  reader = null
  if (!(globalThis as { Bun?: unknown }).Bun) return reader
  try {
    const ffi: any = createRequire(import.meta.url)("bun:ffi")
    const advapi32 = ffi.dlopen("advapi32.dll", {
      CredReadW: { args: ["ptr", "u32", "u32", "ptr"], returns: "i32" },
      CredFree: { args: ["u64"], returns: "void" },
    })
    const kernel32 = ffi.dlopen("kernel32.dll", { GetLastError: { args: [], returns: "u32" } })
    const api: CredentialApi = {
      credRead(target) {
        const name = Buffer.from(`${target}\0`, "utf16le")
        const credential = new BigUint64Array(1)
        if (advapi32.symbols.CredReadW(ffi.ptr(name), CRED_TYPE_GENERIC, 0, ffi.ptr(credential)) === 0) return false
        advapi32.symbols.CredFree(credential[0])
        return true
      },
      lastError: () => kernel32.symbols.GetLastError(),
    }
    reader = (target) => readGenericCredential(api, target)
  } catch {
    // Bun without ffi: its Windows ARM64 build says "dlopen() is not available in this build (TinyCC is disabled)".
  }
  return reader
}
