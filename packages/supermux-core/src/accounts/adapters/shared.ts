import { readFile } from "node:fs/promises"
import { CoreError } from "../../errors.js"
import type { Account } from "../types.js"

export async function readJson(path: string): Promise<Record<string, unknown> | undefined> {
  try {
    const value = JSON.parse(await readFile(path, "utf8"))
    return value && typeof value === "object" && !Array.isArray(value) ? value : undefined
  } catch { return undefined }
}

/** Payload of a JWT without verifying it (identity hints only). */
export function jwtPayload(token: unknown): Record<string, unknown> | undefined {
  if (typeof token !== "string") return undefined
  const part = token.split(".")[1]
  if (!part) return undefined
  try {
    const value = JSON.parse(Buffer.from(part, "base64url").toString("utf8"))
    return value && typeof value === "object" ? value : undefined
  } catch { return undefined }
}

export function str(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined
}

export function requireSecret(account: Account, secret: string | undefined): string {
  if (!secret) throw new CoreError("account_secret_missing", `Account ${account.id} has no secret in the vault`)
  return secret
}

export function unsupported(account: Account): never {
  throw new CoreError("unsupported_operation", `${account.agent} accounts do not support method ${account.method}`)
}

export function epochDate(value: unknown): Date | undefined {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? new Date(value * 1000) : undefined
}
