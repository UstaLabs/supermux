import { expect, test } from "bun:test"
import { spawnSync } from "node:child_process"
import { randomUUID } from "node:crypto"
import { registry, Unsupported } from "@supermux/platform"

// #100: the broker polled the registry Path through two PowerShells every 10 s while git was missing. One RegGetValueW
// call reads it in place, without starting a process.
test("Windows reads one string value of the user's or the machine's registry", () => {
  const asked: string[] = []
  const windowsRead = (hive: registry.Hive, key: string, name: string) => { asked.push(`${hive}|${key}|${name}`); return "%USERPROFILE%\\bin" }
  expect(registry.readString("user", "Environment", "Path", { platform: "win32", windowsRead })).toBe("%USERPROFILE%\\bin")
  expect(asked).toEqual(["user|Environment|Path"])
  expect(registry.readString("machine", "Environment", "Path", { platform: "win32", windowsRead: () => null })).toBeNull()
})

test("a registry this layer cannot read answers Unsupported, never a silent null", () => {
  for (const platform of ["linux", "darwin"] as const) {
    expect(() => registry.readString("user", "Environment", "Path", { platform })).toThrow(Unsupported)
  }
  // Windows without Bun's ffi (Node, or Bun's Windows ARM64 build): nothing can call RegGetValueW.
  expect(() => registry.readString("user", "Environment", "Path", { platform: "win32", windowsRead: null })).toThrow(Unsupported)
})

/** A RegGetValueW over [stored] (UTF-16LE with its terminator), as Windows answers it: the size first, then the bytes. */
function fakeApi(stored: string | null) {
  const calls: (number | null)[] = []
  return {
    calls,
    api: {
      getValue(_hive: registry.Hive, _key: string, _name: string, buffer: Uint8Array | null) {
        calls.push(buffer?.byteLength ?? null)
        if (stored === null) return { status: 2, bytes: 0 }
        const bytes = Buffer.from(`${stored}\0`, "utf16le")
        if (buffer === null) return { status: 0, bytes: bytes.byteLength }
        if (buffer.byteLength < bytes.byteLength) return { status: 234, bytes: bytes.byteLength }
        buffer.set(bytes)
        return { status: 0, bytes: bytes.byteLength }
      },
    },
  }
}

test("RegGetValueW is asked the size, then read; the value comes back raw and without its terminator", () => {
  const value = "C:\\Users\\Hüseyin\\bin;%LOCALAPPDATA%\\x"
  const { api, calls } = fakeApi(value)
  expect(registry.readRegistryString(api, "user", "Environment", "Path")).toBe(value)
  // UTF-16LE: two bytes a character, and the terminator.
  expect(calls).toEqual([null, (value.length + 1) * 2])
})

test("a value that grew between the size and the read is read again at its new size", () => {
  const { api } = fakeApi("C:\\a")
  let grew = false
  const growing = {
    getValue(hive: registry.Hive, key: string, name: string, buffer: Uint8Array | null) {
      if (buffer !== null && !grew) { grew = true; return { status: 234, bytes: 40 } }
      return api.getValue(hive, key, name, buffer)
    },
  }
  expect(registry.readRegistryString(growing, "user", "Environment", "Path")).toBe("C:\\a")
})

test("a missing key or value is null; any other Windows error throws with its code", () => {
  expect(registry.readRegistryString(fakeApi(null).api, "user", "Environment", "Path")).toBeNull()
  expect(registry.readRegistryString(fakeApi("").api, "user", "Environment", "Path")).toBe("")
  const denied = { getValue: () => ({ status: 5, bytes: 0 }) }
  expect(() => registry.readRegistryString(denied, "machine", "Environment", "Path")).toThrow("Windows error 5")
})

test.skipIf(process.platform !== "win32")("the registry: a raw REG_EXPAND_SZ reads back exactly, a missing value is null", () => {
  const key = `Software\\supermux-test\\${randomUUID()}`
  const value = "%USERPROFILE%\\bin;C:\\Users\\Hüseyin\\tools"
  const add = spawnSync("reg", ["add", `HKCU\\${key}`, "/v", "Path", "/t", "REG_EXPAND_SZ", "/d", value, "/f"], { windowsHide: true })
  expect(add.status).toBe(0)
  try {
    expect(registry.readString("user", key, "Path")).toBe(value)
    expect(registry.readString("user", key, "Nothing")).toBeNull()
  } finally {
    spawnSync("reg", ["delete", `HKCU\\${key}`, "/f"], { windowsHide: true })
  }
  expect(registry.readString("user", key, "Path")).toBeNull()
  // The machine's Path, unexpanded: Windows itself keeps %SystemRoot% entries in it.
  expect(registry.readString("machine", "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "Path")?.toLowerCase()).toContain("system32")
})
