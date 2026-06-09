import { existsSync, readFileSync, writeFileSync, renameSync, chmodSync, mkdirSync } from "fs"
import { dirname } from "path"
import webpush from "web-push"

export interface VapidKeys {
  publicKey: string
  privateKey: string
  subject: string
}

export function loadOrGenerateVapid(path: string, subject: string): VapidKeys {
  if (existsSync(path)) {
    try {
      const raw = readFileSync(path, "utf8")
      const parsed = JSON.parse(raw) as VapidKeys
      if (typeof parsed.publicKey !== "string" || typeof parsed.privateKey !== "string") {
        throw new Error(`push-keys.json at ${path} is missing publicKey or privateKey`)
      }
      return parsed
    } catch (err: any) {
      throw new Error(
        `push-keys.json at ${path} is corrupted or invalid: ${err?.message ?? String(err)}. ` +
        `Delete the file to regenerate (existing push subscriptions will be invalidated).`,
      )
    }
  }
  const generated = webpush.generateVAPIDKeys()
  const record: VapidKeys = {
    publicKey: generated.publicKey,
    privateKey: generated.privateKey,
    subject,
  }
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  const tmp = `${path}.tmp`
  writeFileSync(tmp, JSON.stringify(record, null, 2), { mode: 0o600 })
  renameSync(tmp, path)
  chmodSync(path, 0o600)
  return record
}
