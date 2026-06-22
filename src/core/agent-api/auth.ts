// Shared credential helpers for the agent-api adapters: read+parse auth files,
// decode JWT claims (no verify), and run an OAuth refresh-token grant. All I/O is
// injectable (ReadFileFn / FetchFn) so adapters stay unit-testable.

import { readFileSync } from "fs"
import type { FetchFn, ReadFileFn } from "./types"

export const readJson = (read: ReadFileFn, path: string): any | null => {
  try {
    return JSON.parse(read(path))
  } catch {
    return null
  }
}

export const defaultRead: ReadFileFn = (p) => readFileSync(p, "utf8")

// Decode a JWT payload WITHOUT verifying (for claims like chatgpt-account-id).
export function jwtClaims(token: string): any | null {
  const part = token.split(".")[1]
  if (!part) return null
  try {
    return JSON.parse(Buffer.from(part.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8"))
  } catch {
    return null
  }
}

// OAuth refresh-token grant (x-www-form-urlencoded) → JSON token response.
export async function refreshOAuth(f: FetchFn, url: string, body: Record<string, string>): Promise<any> {
  const res = await f(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(body).toString(),
  })
  if (!res.ok) throw new Error(`oauth refresh ${res.status}`)
  return res.json()
}
