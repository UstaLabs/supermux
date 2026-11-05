// The OS credential store, read-only: whether it holds a generic credential, never what is in it. Windows' Credential
// Manager is read through Bun's ffi (advapi32 CredReadW), loaded only on win32 and only under Bun, so Node still loads
// this package. Where the store cannot be read, the answer is Unsupported, never a silent "no".
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
 * on a Windows runtime without Bun's ffi, it throws [Unsupported].
 */
export function hasGenericCredential(service: string, account: string, options: CredentialOptions = {}): boolean {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") throw new Unsupported("credentials.hasGenericCredential", `reading the credential store is not implemented on ${platform} yet`)
  const read = options.windowsRead === undefined ? windowsReader() : options.windowsRead
  if (!read) throw new Unsupported("credentials.hasGenericCredential", "reading Windows Credential Manager needs Bun's ffi, which this runtime does not have")
  return read(`${service}:${account}`)
}

const CRED_TYPE_GENERIC = 1
let reader: ((target: string) => boolean) | null | undefined

/** CredReadW, then CredFree at once; null where there is no Bun ffi to call it with. */
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
    reader = (target) => {
      const name = Buffer.from(`${target}\0`, "utf16le")
      const credential = new BigUint64Array(1)
      if (advapi32.symbols.CredReadW(ffi.ptr(name), CRED_TYPE_GENERIC, 0, ffi.ptr(credential)) === 0) return false
      advapi32.symbols.CredFree(credential[0])
      return true
    }
  } catch {
    // Bun without ffi: its Windows ARM64 build says "dlopen() is not available in this build (TinyCC is disabled)".
  }
  return reader
}
