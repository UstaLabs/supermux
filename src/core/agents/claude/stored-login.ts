/** Claude Code's login in the OS credential store — PRIVATE to the claude module.
 *
 * Claude Code writes its login (the JSON with `claudeAiOauth`) to <config dir>/.credentials.json, except on Windows when
 * Anthropic's `tengu_windows_credman` rollout is on for the account: then it keeps it in Credential Manager through
 * Bun.secrets and deletes the file. The layout below is 2.1.311's, read from its Windows build (#101): service
 * "Claude Code-credentials", plus "-<first 8 hex of sha256(config dir)>" when CLAUDE_CONFIG_DIR is set; name
 * "claude-code-user". A login over 2400 bytes is stored as base64 in "claude-code-user#0", "#1", … under a manifest
 * "claude-code-user#m" = {"n": chunks, "l": base64 length}.
 */
import { createHash } from "node:crypto"
import { credentials, Unsupported } from "@supermux/platform"

const SERVICE = "Claude Code-credentials"
const NAME = "claude-code-user"
const CHUNK_CHARS = 2400
const MAX_CHUNKS = 256

/** Reads one secret by service and name; null when there is none. */
export type SecretRead = (service: string, name: string) => Promise<string | null>

/** The Bun.secrets service Claude Code keeps the login of [configDir] (its CLAUDE_CONFIG_DIR; unset = ~/.claude) under. */
export function claudeStoredLoginService(configDir?: string): string {
  if (!configDir) return SERVICE
  return `${SERVICE}-${createHash("sha256").update(configDir.normalize("NFC")).digest("hex").slice(0, 8)}`
}

/**
 * The login JSON Claude Code keeps in the OS credential store for [configDir], or null when it keeps none there or this
 * OS's store is not read (only Windows' is). Throws when the store fails, or when a split login does not add up.
 */
export async function readClaudeStoredLogin(configDir?: string, read?: SecretRead, platform?: NodeJS.Platform): Promise<string | null> {
  const get = read ?? platformRead(platform)
  const service = claudeStoredLoginService(configDir)
  const manifest = chunkManifest(await get(service, `${NAME}#m`))
  if (!manifest) return get(service, NAME)
  const chunks = await Promise.all(Array.from({ length: manifest.n }, (_, i) => get(service, `${NAME}#${i}`)))
  const joined = chunks.join("")
  if (chunks.some((chunk) => chunk == null) || joined.length !== manifest.l) {
    throw new Error(`Claude's stored sign-in is incomplete (${joined.length} of ${manifest.l} characters in ${manifest.n} parts)`)
  }
  return Buffer.from(joined, "base64").toString("utf8")
}

/**
 * Whether Claude Code keeps a login for [configDir] in the OS credential store: the whole entry, or a split one's
 * manifest. Synchronous (CredReadW), for the status badge and a sign-in's success check; the secret is never read.
 * EVERY error reads as no, as for agy's keyring login: off Windows, a runtime without Bun's ffi, a Windows error.
 */
export function claudeStoredLoginPresent(
  configDir?: string,
  windowsRead?: ((target: string) => boolean) | null,
  platform?: NodeJS.Platform,
): boolean {
  const service = claudeStoredLoginService(configDir)
  const options = { ...(platform !== undefined ? { platform } : {}), ...(windowsRead !== undefined ? { windowsRead } : {}) }
  try {
    return credentials.hasSecret(service, NAME, options) || credentials.hasSecret(service, `${NAME}#m`, options)
  } catch {
    return false
  }
}

/** The manifest of a split login, as Claude Code itself accepts one; anything else means the login is stored whole. */
function chunkManifest(text: string | null): { n: number; l: number } | null {
  if (!text) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  const { n, l } = (parsed ?? {}) as { n?: unknown; l?: unknown }
  if (!Number.isInteger(n) || !Number.isInteger(l)) return null
  const chunks = n as number, length = l as number
  return chunks > 0 && chunks <= MAX_CHUNKS && length > 0 && length <= chunks * CHUNK_CHARS ? { n: chunks, l: length } : null
}

/** The platform layer's store, where a store this OS has no reader for is simply empty. */
function platformRead(platform?: NodeJS.Platform): SecretRead {
  return async (service, name) => {
    try {
      return await credentials.readSecret(service, name, platform ? { platform } : {})
    } catch (err) {
      if (err instanceof Unsupported) return null
      throw err
    }
  }
}
