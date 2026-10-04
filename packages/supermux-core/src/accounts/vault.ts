import { chmod, mkdir, open, readFile, rename, rm } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { isAbsolute, join } from "node:path"
import { CoreError } from "../errors.js"
import type { Vault } from "./types.js"

const VAULT_ID = /^[a-zA-Z0-9_-]{1,128}$/

export function assertVaultId(id: string): void {
  if (typeof id !== "string" || !VAULT_ID.test(id)) throw new CoreError("invalid_account_id", "Invalid account ID")
}

function assertSecret(secret: string): void {
  if (typeof secret !== "string" || !secret) throw new CoreError("invalid_input", "secret must be a nonempty string")
}

/** One file per secret: dir 0700, files 0600, written to a temp file then renamed. No encryption. */
export function fileVault(directory: string): Vault {
  if (typeof directory !== "string" || !isAbsolute(directory)) throw new CoreError("invalid_options", "Vault directory must be absolute")
  let ready: Promise<void> | undefined
  const ensure = () => ready ??= (async () => {
    await mkdir(directory, { recursive: true, mode: 0o700 })
    await chmod(directory, 0o700)
  })().catch(error => { ready = undefined; throw error })
  const path = (id: string) => { assertVaultId(id); return join(directory, `${id}.secret`) }
  return {
    async get(id) {
      try { return await readFile(path(id), "utf8") } catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined
        throw error
      }
    },
    async put(id, secret) {
      const target = path(id)
      assertSecret(secret)
      await ensure()
      const temp = `${target}.${randomUUID()}.tmp`
      try {
        const file = await open(temp, "wx", 0o600)
        try { await file.writeFile(secret); await file.sync() } finally { await file.close() }
        await rename(temp, target)
      } finally { await rm(temp, { force: true }) }
    },
    async delete(id) { await rm(path(id), { force: true }) },
  }
}

/** In-memory vault for tests and hosts that keep secrets elsewhere. */
export function memoryVault(initial: Record<string, string> = {}): Vault {
  const secrets = new Map(Object.entries(initial))
  return {
    async get(id) { assertVaultId(id); return secrets.get(id) },
    async put(id, secret) { assertVaultId(id); assertSecret(secret); secrets.set(id, secret) },
    async delete(id) { assertVaultId(id); secrets.delete(id) },
  }
}
