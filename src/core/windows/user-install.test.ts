import { describe, expect, test } from "bun:test"
import {
  addToUserPath, fromUtf16Base64, powershellUserPathStore, readPathScript, toUtf16Base64, type PowerShellRunner,
  type UserPathStore,
} from "./user-install"

const NON_ASCII = "C:\\Users\\Hüseyin\\bin;C:\\Tëst\\Çağrı\\bin;%USERPROFILE%\\.local\\bin"

/** A fake PowerShell holding the user's registry Path; it speaks only the base64 protocol. */
function fakePowerShell(initial: string | null) {
  let path = initial
  const scripts: string[] = []
  const run: PowerShellRunner = async (script, env = {}) => {
    scripts.push(script)
    if (script === readPathScript("user")) return path === null ? "" : toUtf16Base64(path) + "\r\n"
    if (env.MUX_NEW_USER_PATH_B64 !== undefined) {
      path = Buffer.from(env.MUX_NEW_USER_PATH_B64, "base64").toString("utf16le")
      return ""
    }
    throw new Error("unexpected script")
  }
  return { run, scripts, get path() { return path } }
}

describe("the user PATH crosses PowerShell as base64 UTF-16LE", () => {
  test("base64 UTF-16LE round-trips any text, and is plain ASCII", () => {
    const b = toUtf16Base64(NON_ASCII)
    expect(/^[A-Za-z0-9+/=]+$/.test(b)).toBe(true)
    expect(fromUtf16Base64(b)).toBe(NON_ASCII)
  })

  test("a non-ASCII entry survives read + add + write byte for byte", async () => {
    const ps = fakePowerShell(NON_ASCII)
    const store = powershellUserPathStore(ps.run)
    expect(await store.readUserPath()).toBe(NON_ASCII)
    expect(await addToUserPath(store, "C:\\Users\\Hüseyin\\.grok\\bin", {})).toBe(true)
    expect(ps.path).toBe(`${NON_ASCII};C:\\Users\\Hüseyin\\.grok\\bin`)
    // the scripts never carry the value itself
    expect(ps.scripts.some((s) => s.includes("Hüseyin"))).toBe(false)
  })

  test("an unset Path reads as null", async () => {
    expect(await powershellUserPathStore(fakePowerShell(null).run).readUserPath()).toBeNull()
  })

  test("a PATH read with U+FFFD is never written back", async () => {
    let wrote = false
    const damaged: UserPathStore = {
      readUserPath: async () => "C:\\Users\\H\uFFFDseyin\\bin",
      writeUserPath: async () => { wrote = true },
    }
    const err = await addToUserPath(damaged, "C:\\x", {}).catch((e) => e)
    expect(String(err)).toContain("U+FFFD")
    expect(wrote).toBe(false)
    const ps = fakePowerShell("C:\\a")
    const err2 = await powershellUserPathStore(ps.run).writeUserPath("C:\\\uFFFD").catch((e) => e)
    expect(String(err2)).toContain("U+FFFD")
    expect(ps.path).toBe("C:\\a")
  })
})

describe("concurrent PATH adds", () => {
  test("two adds at once both land (serialised, re-read before the write)", async () => {
    let path: string | null = "C:\\Tools"
    const tick = () => new Promise((r) => setTimeout(r, 5))
    const store: UserPathStore = {
      readUserPath: async () => { const v = path; await tick(); return v },
      writeUserPath: async (v) => { await tick(); path = v },
    }
    const [a, b] = await Promise.all([addToUserPath(store, "C:\\A", {}), addToUserPath(store, "C:\\B", {})])
    expect([a, b]).toEqual([true, true])
    expect(path).toBe("C:\\Tools;C:\\A;C:\\B")
  })

  test("a change another program makes between the read and the write is kept", async () => {
    let path: string | null = "C:\\Tools"
    let reads = 0
    const store: UserPathStore = {
      readUserPath: async () => {
        reads++
        if (reads === 2) path = "C:\\Tools;C:\\Other" // someone else wrote meanwhile
        return path
      },
      writeUserPath: async (v) => { path = v },
    }
    await addToUserPath(store, "C:\\Mine", {})
    expect(path).toBe("C:\\Tools;C:\\Other;C:\\Mine")
  })

  test("a failed add doesn't block the next one", async () => {
    const bad: UserPathStore = { readUserPath: async () => { throw new Error("reg") }, writeUserPath: async () => {} }
    await addToUserPath(bad, "C:\\A", {}).catch(() => {})
    let path: string | null = null
    const good: UserPathStore = { readUserPath: async () => path, writeUserPath: async (v) => { path = v } }
    expect(await addToUserPath(good, "C:\\B", {})).toBe(true)
  })
})
