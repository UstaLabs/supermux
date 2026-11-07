// Windows' registry, read-only: one string value, raw. Read in place through Bun's ffi (advapi32 RegGetValueW), loaded
// only on win32 and only under Bun, so Node still loads this package. A REG_EXPAND_SZ comes back unexpanded, so its
// %USERPROFILE%-style entries stay variables. Where the registry cannot be read, the answer is Unsupported, never a
// silent null.
import { createRequire } from "node:module"
import { Unsupported } from "./errors.js"

/** HKEY_CURRENT_USER or HKEY_LOCAL_MACHINE. */
export type Hive = "user" | "machine"

export interface RegistryOptions {
  /** The OS to ask; the running one by default (tests force the others). */
  platform?: NodeJS.Platform
  /** Reads one value; null where Bun's ffi is missing (Node, Bun's Windows ARM64 build). */
  windowsRead?: ((hive: Hive, key: string, name: string) => string | null) | null
}

/**
 * The raw string [name] under [key] in [hive] (a REG_SZ or REG_EXPAND_SZ, unexpanded), or null when the key or the
 * value does not exist. Off Windows, and on a Windows runtime without Bun's ffi, it throws [Unsupported]; any other
 * Windows error throws too.
 */
export function readString(hive: Hive, key: string, name: string, options: RegistryOptions = {}): string | null {
  const platform = options.platform ?? process.platform
  if (platform !== "win32") throw new Unsupported("registry.readString", `there is no registry on ${platform}`)
  const read = options.windowsRead === undefined ? windowsReader() : options.windowsRead
  if (!read) throw new Unsupported("registry.readString", "reading the Windows registry needs Bun's ffi, which this runtime does not have")
  return read(hive, key, name)
}

const ERROR_SUCCESS = 0
const ERROR_FILE_NOT_FOUND = 2
const ERROR_MORE_DATA = 234

/** @internal The Windows call behind [readString], as a seam for tests off Windows. */
export interface RegistryApi {
  /**
   * RegGetValueW of a string value, raw, into [buffer]: its status and the bytes it wrote, or the bytes it needs (with
   * no buffer: ERROR_SUCCESS; with one too small: ERROR_MORE_DATA).
   */
  getValue(hive: Hive, key: string, name: string, buffer: Uint8Array | null): { status: number; bytes: number }
}

/**
 * @internal [name]'s string through [api]: its size first, then its bytes, as UTF-16LE without the terminator. A value
 * that grew in between is read again at its new size; ERROR_FILE_NOT_FOUND (the key or the value) is null.
 */
export function readRegistryString(api: RegistryApi, hive: Hive, key: string, name: string): string | null {
  let buffer: Uint8Array | null = null
  for (let attempt = 0; attempt < 5; attempt++) {
    const { status, bytes } = api.getValue(hive, key, name, buffer)
    if (status === ERROR_FILE_NOT_FOUND) return null
    if (status === ERROR_SUCCESS && buffer !== null) {
      return Buffer.from(buffer.buffer, buffer.byteOffset, bytes).toString("utf16le").replace(/\0+$/, "")
    }
    if (status === ERROR_SUCCESS && bytes === 0) return ""
    if (status !== ERROR_SUCCESS && status !== ERROR_MORE_DATA) {
      throw new Error(`RegGetValueW for ${hive}\\${key}\\${name} failed with Windows error ${status}`)
    }
    buffer = new Uint8Array(bytes)
  }
  throw new Error(`RegGetValueW for ${hive}\\${key}\\${name} kept growing while it was read`)
}

// The predefined handles, sign-extended to 64 bits as Windows' headers make them: ((HKEY)(ULONG_PTR)((LONG)0x8000000x)).
const HIVES: Record<Hive, bigint> = { user: 0xffffffff80000001n, machine: 0xffffffff80000002n }
// RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ | RRF_NOEXPAND: a string value of either kind, never expanded.
const RAW_STRING = 0x2 | 0x4 | 0x10000000

let reader: ((hive: Hive, key: string, name: string) => string | null) | null | undefined

/** [readRegistryString] over advapi32; null where there is no Bun ffi to call it with. */
function windowsReader(): ((hive: Hive, key: string, name: string) => string | null) | null {
  if (reader !== undefined) return reader
  reader = null
  if (!(globalThis as { Bun?: unknown }).Bun) return reader
  try {
    const ffi: any = createRequire(import.meta.url)("bun:ffi")
    const advapi32 = ffi.dlopen("advapi32.dll", {
      RegGetValueW: { args: ["u64", "ptr", "ptr", "u32", "ptr", "ptr", "ptr"], returns: "i32" },
    })
    const api: RegistryApi = {
      getValue(hive, key, name, buffer) {
        const subKey = Buffer.from(`${key}\0`, "utf16le")
        const value = Buffer.from(`${name}\0`, "utf16le")
        const size = new Uint32Array([buffer?.byteLength ?? 0])
        const status = advapi32.symbols.RegGetValueW(
          HIVES[hive], ffi.ptr(subKey), ffi.ptr(value), RAW_STRING, null, buffer ? ffi.ptr(buffer) : null, ffi.ptr(size),
        )
        return { status, bytes: size[0]! }
      },
    }
    reader = (hive, key, name) => readRegistryString(api, hive, key, name)
  } catch {
    // Bun without ffi: its Windows ARM64 build says "dlopen() is not available in this build (TinyCC is disabled)".
  }
  return reader
}
