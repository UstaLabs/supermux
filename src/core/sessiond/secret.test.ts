import { afterEach, describe, expect, test } from "bun:test"
import { mkdtemp, readFile, readdir, rm, stat, symlink, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createOrLoadSessiondSecret, sessiondEndpoint, timingSafeSecretEqual } from "./secret"

const dirs: string[] = []
afterEach(async () => Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))))

async function stateDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), "sessiond-secret-"))
  dirs.push(dir)
  return dir
}

describe("sessiond secret", () => {
  test("concurrent creators converge on one canonical 32-byte base64 value", async () => {
    const dir = await stateDir()
    const values = await Promise.all(Array.from({ length: 12 }, () => createOrLoadSessiondSecret(dir)))
    expect(new Set(values).size).toBe(1)
    expect(Buffer.from(values[0]!, "base64")).toHaveLength(32)
    expect(Buffer.from(values[0]!, "base64").toString("base64")).toBe(values[0]!)
    expect(await readFile(join(dir, "sessiond.secret"), "utf8")).toBe(values[0]!)
    expect((await readdir(dir)).filter(name => name.startsWith("sessiond.secret.tmp."))).toEqual([])
    if (process.platform !== "win32") expect((await stat(join(dir, "sessiond.secret"))).mode & 0o777).toBe(0o600)
  })

  test("rejects symlink and FIFO final paths without following or reading them", async () => {
    const dir = await stateDir()
    const target = join(dir, "target")
    await writeFile(target, Buffer.alloc(32).toString("base64"), { mode: 0o600 })
    await symlink(target, join(dir, "sessiond.secret"))
    await expect(createOrLoadSessiondSecret(dir)).rejects.toThrow("regular file")
    await rm(join(dir, "sessiond.secret"))
    if (process.platform !== "win32") {
      const made = Bun.spawnSync(["mkfifo", join(dir, "sessiond.secret")])
      expect(made.exitCode).toBe(0)
      await expect(createOrLoadSessiondSecret(dir)).rejects.toThrow("regular file")
    }
  })

  test("malformed and wrong-length existing secrets fail loudly", async () => {
    const dir = await stateDir()
    await writeFile(join(dir, "sessiond.secret"), "not base64", { mode: 0o600 })
    await expect(createOrLoadSessiondSecret(dir)).rejects.toThrow("invalid sessiond secret")
    await writeFile(join(dir, "sessiond.secret"), Buffer.alloc(31).toString("base64"), { mode: 0o600 })
    await expect(createOrLoadSessiondSecret(dir)).rejects.toThrow("32 bytes")
  })

  test("comparison is timing safe for equal and different lengths", () => {
    expect(timingSafeSecretEqual("same", "same")).toBe(true)
    expect(timingSafeSecretEqual("same", "different-length")).toBe(false)
    expect(timingSafeSecretEqual("same", "nope")).toBe(false)
  })

  test("endpoint is a POSIX socket or a bounded stable Windows pipe", async () => {
    const dir = await stateDir()
    expect(sessiondEndpoint(dir, "linux")).toBe(join(dir, "sessiond.sock"))
    const first = sessiondEndpoint(dir, "win32")
    expect(first).toBe(sessiondEndpoint(dir, "win32"))
    expect(first).toStartWith("\\\\.\\pipe\\supermux-sessiond-")
    expect(first.length).toBeLessThan(120)
    expect(first).not.toBe(sessiondEndpoint(join(dir, "other"), "win32"))
  })
})
