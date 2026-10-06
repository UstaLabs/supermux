import { afterEach, expect, test } from "bun:test"
import { mkdtemp, readdir, readFile, rm, stat } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileVault, memoryVault } from "../src/accounts/index.js"

const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-vault-")); dirs.push(dir); return dir }

test("file vault: 0700 dir, 0600 files, round trip, delete, missing is undefined", async () => {
  const dir = join(await scratch(), "vault")
  const vault = fileVault(dir)
  expect(await vault.get("a")).toBeUndefined()
  await vault.put("a", "s3cret")
  expect(await vault.get("a")).toBe("s3cret")
  expect((await stat(dir)).mode & 0o777).toBe(0o700)
  expect((await stat(join(dir, "a.secret"))).mode & 0o777).toBe(0o600)
  await vault.put("a", "rotated")
  expect(await readFile(join(dir, "a.secret"), "utf8")).toBe("rotated")
  expect((await readdir(dir)).filter(name => name.endsWith(".tmp"))).toEqual([])
  await vault.delete("a")
  await vault.delete("a")
  expect(await vault.get("a")).toBeUndefined()
})

test("vault ids are validated (no path escapes) and secrets must be nonempty", async () => {
  const dir = await scratch()
  for (const vault of [fileVault(join(dir, "v")), memoryVault()]) {
    for (const id of ["../x", "a/b", "", "x".repeat(129), "a:b"]) {
      await expect(vault.put(id, "s")).rejects.toMatchObject({ code: "invalid_account_id" })
      await expect(vault.get(id)).rejects.toMatchObject({ code: "invalid_account_id" })
    }
    await expect(vault.put("ok", "")).rejects.toMatchObject({ code: "invalid_input" })
  }
  expect(() => fileVault("relative/dir")).toThrow("absolute")
})

test("memory vault round trip", async () => {
  const vault = memoryVault({ seeded: "v" })
  expect(await vault.get("seeded")).toBe("v")
  await vault.put("n", "x")
  await vault.delete("seeded")
  expect([await vault.get("seeded"), await vault.get("n")]).toEqual([undefined, "x"])
})
